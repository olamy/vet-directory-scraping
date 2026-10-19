package vetdb.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;

/**
 * Polite HTTP client (Jetty HttpClient) with a disk cache.
 * Each host gets its own minimum delay between requests, and every successful
 * response is kept on disk so a re-run (or a parser fix) never hits the site again.
 */
public final class Fetcher implements AutoCloseable {

    public record Response(int status, String body, boolean fromCache) {
        public boolean ok() {
            return status >= 200 && status < 300 && body != null;
        }
    }

    /** Paths that robots.txt asks us to stay away from. */
    private static final List<String> DENIED = List.of(
        "https://ratemyvet.com.au/search");

    private static final long DEFAULT_DELAY_MS = 1000;
    private static final int MAX_ATTEMPTS = 4;
    // BestForPet profile pages are over 1 MB, so lift Jetty's 2 MB default a bit.
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private final Path cacheDir;
    private final boolean offline;
    private final boolean refresh;
    private final HttpClient client;
    private final Map<String, Long> delays = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequest = new ConcurrentHashMap<>();
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final AtomicInteger networkCalls = new AtomicInteger();

    public Fetcher(Path cacheDir, boolean offline, boolean refresh, String userAgent) {
        this.cacheDir = cacheDir;
        this.offline = offline;
        this.refresh = refresh;
        this.client = new HttpClient();
        client.setFollowRedirects(true);
        client.setConnectTimeout(20_000);
        client.setUserAgentField(new HttpField(HttpHeader.USER_AGENT, userAgent));
        try {
            client.start();
        } catch (Exception e) {
            throw new IllegalStateException("could not start the Jetty HttpClient", e);
        }
    }

    /** Sets the minimum gap between two requests to the same host. */
    public Fetcher delay(String host, long millis) {
        delays.put(host, millis);
        return this;
    }

    public boolean offline() {
        return offline;
    }

    public int networkCalls() {
        return networkCalls.get();
    }

    /** GET returning the body, or null on 4xx or when offline and not cached. */
    public String get(String url) {
        Response r = fetch("GET", url, null, Map.of());
        return r.ok() ? r.body() : null;
    }

    public Response fetch(String method, String url, String body, Map<String, String> headers) {
        for (String denied : DENIED) {
            if (url.startsWith(denied)) {
                throw new IllegalArgumentException("robots.txt disallows " + url);
            }
        }
        Path cached = cachePath(method, url, body);
        if (!refresh && Files.exists(cached)) {
            Response r = readCache(cached);
            if (r != null) {
                return r;
            }
        }
        if (offline) {
            return new Response(-1, null, false);
        }
        Response r = callWithRetry(method, url, body, headers);
        // Keep successes and permanent misses; skip anything that might work later.
        if (r.ok() || r.status() == 404 || r.status() == 410) {
            writeCache(cached, r);
        }
        return r;
    }

    private Response callWithRetry(String method, String url, String body, Map<String, String> headers) {
        String host = URI.create(url).getHost();
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            waitForTurn(host);
            try {
                Request request = client.newRequest(url)
                    .method(HttpMethod.fromString(method))
                    .timeout(60, TimeUnit.SECONDS)
                    .headers(h -> headers.forEach(h::put));
                if (body != null) {
                    String contentType = headers.getOrDefault("Content-Type", "application/json");
                    request.body(new StringRequestContent(contentType, body));
                }
                networkCalls.incrementAndGet();
                ContentResponse resp = new CompletableResponseListener(request, MAX_BODY_BYTES).send().get();
                int status = resp.getStatus();
                if (status == 429 || status >= 500) {
                    String retryAfter = resp.getHeaders().get(HttpHeader.RETRY_AFTER);
                    long wait = retryAfter != null ? parseSeconds(retryAfter) : 10L * attempt * attempt;
                    System.err.printf("  %d from %s, waiting %ds (attempt %d)%n", status, host, wait, attempt);
                    sleep(wait * 1000);
                    continue;
                }
                return new Response(status, decode(resp), false);
            } catch (ExecutionException e) {
                lastError = e.getCause();
                System.err.printf("  network error on %s: %s (attempt %d)%n", url, lastError, attempt);
                sleep(5000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.err.println("  giving up on " + url + (lastError != null ? ": " + lastError : ""));
        return new Response(-1, null, false);
    }

    private static String decode(ContentResponse resp) {
        Charset charset = StandardCharsets.UTF_8;
        String encoding = resp.getEncoding();
        if (encoding != null) {
            try {
                charset = Charset.forName(encoding);
            } catch (RuntimeException ignored) {
                // Unknown charset name: UTF-8 is the safe bet for these sites.
            }
        }
        return new String(resp.getContent(), charset);
    }

    private void waitForTurn(String host) {
        Object lock = hostLocks.computeIfAbsent(host, h -> new Object());
        synchronized (lock) {
            long gap = delays.getOrDefault(host, DEFAULT_DELAY_MS);
            long last = lastRequest.getOrDefault(host, 0L);
            long wait = last + gap - System.currentTimeMillis();
            if (wait > 0) {
                sleep(wait);
            }
            lastRequest.put(host, System.currentTimeMillis());
        }
    }

    private Path cachePath(String method, String url, String body) {
        String host = URI.create(url).getHost();
        String key = method + " " + url + (body == null ? "" : "\n" + body);
        return cacheDir.resolve(host).resolve(sha256(key) + ".gz");
    }

    private static Response readCache(Path file) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int nl = all.indexOf('\n');
            int status = Integer.parseInt(all.substring(0, nl));
            return new Response(status, all.substring(nl + 1), true);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeCache(Path file, Response r) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write((r.status() + "\n").getBytes(StandardCharsets.UTF_8));
                if (r.body() != null) {
                    out.write(r.body().getBytes(StandardCharsets.UTF_8));
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("  could not write cache " + file + ": " + e.getMessage());
        }
    }

    private static long parseSeconds(String value) {
        try {
            return Math.max(1, Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            client.stop();
        } catch (Exception e) {
            System.err.println("  could not stop the HttpClient cleanly: " + e.getMessage());
        }
    }
}
