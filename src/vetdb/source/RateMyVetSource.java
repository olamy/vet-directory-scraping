package vetdb.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vetdb.clean.Normalizer;
import vetdb.http.Fetcher;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/**
 * ratemyvet.com.au: every clinic is in the sitemap, and each profile has JSON-LD
 * plus a "Practice details" grid. robots.txt forbids /search and /search-index.json.
 */
public final class RateMyVetSource implements Source {

    public static final String NAME = "ratemyvet";
    private static final String BASE = "https://ratemyvet.com.au";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern RATING = Pattern.compile("([0-9.]+) out of 5");
    private static final Pattern REVIEWS = Pattern.compile("([0-9][0-9,]*) Google review");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String host() {
        return "ratemyvet.com.au";
    }

    @Override
    public long delayMillis() {
        return 1500;
    }

    @Override
    public void run(Fetcher fetcher, int limit, Consumer<SourceRecord> sink) {
        List<String> urls = discover(fetcher);
        int total = limit > 0 ? Math.min(limit, urls.size()) : urls.size();
        System.out.printf("[%s] %d clinic pages in the sitemap, processing %d%n", NAME, urls.size(), total);
        for (int i = 0; i < total; i++) {
            String url = urls.get(i);
            String html = fetcher.get(url);
            if (html == null) {
                System.err.printf("[%s] could not get %s%n", NAME, url);
                continue;
            }
            SourceRecord r = parse(url, html);
            if (r != null) {
                sink.accept(r);
            }
            if ((i + 1) % 100 == 0) {
                System.out.printf("[%s] %d/%d%n", NAME, i + 1, total);
            }
        }
    }

    private List<String> discover(Fetcher fetcher) {
        List<String> urls = new ArrayList<>();
        for (String sitemap : Extract.sitemapLocs(fetcher.get(BASE + "/sitemap-index.xml"))) {
            for (String loc : Extract.sitemapLocs(fetcher.get(sitemap))) {
                if (loc.contains("/vet/") && !urls.contains(loc)) {
                    urls.add(loc);
                }
            }
        }
        return urls;
    }

    public static SourceRecord parse(String url, String html) {
        Document doc = Jsoup.parse(html, url);
        String slug = Extract.slug(url, "/vet/");
        if (slug == null) {
            return null;
        }
        SourceRecord r = new SourceRecord(NAME, slug, BASE + "/vet/" + slug + "/");

        JsonNode ld = businessJsonLd(doc);
        if (ld != null) {
            r.set(Field.NAME, ld.path("name").asText(null));
            JsonNode a = ld.path("address");
            r.set(Field.STREET, a.path("streetAddress").asText(null));
            r.set(Field.SUBURB, a.path("addressLocality").asText(null));
            r.set(Field.STATE, a.path("addressRegion").asText(null));
            r.set(Field.POSTCODE, a.path("postalCode").asText(null));
            r.country(a.path("addressCountry").asText(null));
            JsonNode geo = ld.path("geo");
            r.set(Field.LATITUDE, geo.path("latitude").asText(null));
            r.set(Field.LONGITUDE, geo.path("longitude").asText(null));
            r.set(Field.PHONE, ld.path("telephone").asText(null));
            for (JsonNode same : ld.path("sameAs")) {
                // sameAs holds the clinic website; Normalizer moves social pages to their own columns.
                if (!r.has(Field.WEBSITE)) {
                    r.set(Field.WEBSITE, same.asText(null));
                }
            }
        }
        if (!r.has(Field.NAME)) {
            r.set(Field.NAME, Extract.text(doc.selectFirst("h1")));
        }
        if (!r.has(Field.STATE)) {
            // Some profiles only know the state; it is still in the breadcrumb (/nsw, /vic...).
            for (Element crumb : doc.select("nav.crumbs a[href]")) {
                String path = crumb.attr("href").replaceAll("/", "");
                if (Normalizer.STATES.contains(path.toUpperCase())) {
                    r.set(Field.STATE, path.toUpperCase());
                }
            }
        }

        Element hero = doc.selectFirst("section.profile-hero");
        if (hero != null) {
            String sub = Extract.text(hero.selectFirst("p.sub"));
            if (sub != null) {
                r.set(Field.CATEGORY, sub.split("\\s+·\\s+")[0]);
            }
            r.set(Field.GOOGLE_RATING, Extract.group(RATING, Extract.attr(hero.selectFirst("span.stars"), "aria-label"), 1));
            for (Element s : hero.select("span.sub")) {
                String count = Extract.group(REVIEWS, s.text(), 1);
                if (count != null) {
                    r.set(Field.GOOGLE_REVIEW_COUNT, count);
                }
            }
        }

        List<String> services = new ArrayList<>();
        doc.select("section.profile-hero .tag-row .badge").forEach(b -> services.add(b.text()));
        Element servicesHeading = doc.selectFirst("h3:containsOwn(Services)");
        if (servicesHeading != null && servicesHeading.nextElementSibling() != null) {
            servicesHeading.nextElementSibling().select(".card strong").forEach(s -> services.add(s.text()));
        }
        r.set(Field.SERVICES, String.join("; ", services));

        Map<String, Element> details = new LinkedHashMap<>();
        for (Element row : doc.select(".detail-grid .detail-row")) {
            Element dv = row.selectFirst("span.dv");
            String key = Extract.text(row.selectFirst("span.dk"));
            if (key != null && dv != null && !dv.hasClass("missing")) {
                details.put(key.toLowerCase(), dv);
            }
        }
        r.set(Field.SPECIES_TREATED, Extract.text(details.get("species treated")));
        r.set(Field.OPENING_HOURS, Extract.text(details.get("opening hours")));
        r.set(Field.AFTER_HOURS_EMERGENCY, Extract.yesNo(Extract.text(details.get("after-hours & emergency"))));
        r.set(Field.HOME_MOBILE_VISITS, Extract.yesNo(Extract.text(details.get("home & mobile visits"))));
        Element booking = details.get("online booking");
        if (booking != null) {
            String link = Extract.attr(booking.selectFirst("a[href^=http]"), "href");
            r.set(Field.BOOKING_URL, link);
            r.set(Field.BOOK_ONLINE, link != null ? "Y" : Extract.yesNo(booking.text()));
        }
        Element payments = details.get("payment methods");
        if (payments != null) {
            Extract.payments(r, List.of(payments.text().split("\\s*[,;]\\s*")));
        }
        return r;
    }

    private static JsonNode businessJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = JSON.readTree(script.data());
                String type = node.path("@type").toString();
                if (type.contains("VeterinaryCare") || type.contains("LocalBusiness")) {
                    return node;
                }
            } catch (Exception e) {
                // Broken JSON-LD block: fall back to the HTML.
            }
        }
        return null;
    }
}
