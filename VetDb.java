///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//DEPS info.picocli:picocli:4.7.6
//DEPS org.eclipse.jetty:jetty-client:12.1.14
//DEPS org.eclipse.jetty:jetty-slf4j-impl:12.1.14
//DEPS org.jsoup:jsoup:1.18.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.18.2
//DEPS org.xerial:sqlite-jdbc:3.47.1.0
//DEPS org.apache.commons:commons-csv:1.12.0
//DEPS org.apache.commons:commons-text:1.13.0
//DEPS com.googlecode.libphonenumber:libphonenumber:8.13.52
//DEPS org.dhatim:fastexcel:0.18.4
//SOURCES src/vetdb/**/*.java

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import vetdb.clean.Normalizer;
import vetdb.enrich.AbrClient;
import vetdb.enrich.GooglePlacesClient;
import vetdb.export.Exporter;
import vetdb.http.Fetcher;
import vetdb.merge.Merger;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.SourceRecord;
import vetdb.source.BestForPetSource;
import vetdb.source.PiaSource;
import vetdb.source.RateMyVetSource;
import vetdb.source.Source;
import vetdb.source.VetNetSource;
import vetdb.store.Db;

@Command(name = "vetdb", mixinStandardHelpOptions = true, version = "vetdb 1.0",
    description = "Builds one database of Australian vet clinics from several online directories.",
    subcommands = {VetDb.Fetch.class, VetDb.Parse.class, VetDb.EnrichAbr.class, VetDb.EnrichGoogle.class,
        VetDb.Merge.class, VetDb.Export.class, VetDb.All.class})
public class VetDb implements Runnable {

    static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) vet-directory-builder/1.0";
    static final String ABR_FOOTER = "%nEnvironment:%n  ABR_GUID   free GUID from https://abr.business.gov.au/Tools/WebServices"
        + "%n             (the step is skipped when it is not set)";
    static final String GOOGLE_FOOTER = "%nEnvironment:%n  GOOGLE_PLACES_API_KEY   key of a billed Google Cloud project"
        + "%n                          (the step is skipped when it is not set)";
    static final String BOTH_FOOTER = "%nEnvironment:"
        + "%n  ABR_GUID                free GUID from https://abr.business.gov.au/Tools/WebServices"
        + "%n  GOOGLE_PLACES_API_KEY   key of a billed Google Cloud project"
        + "%n  Each enrichment step is skipped when its variable is not set.";

    public static void main(String[] args) {
        // Keep Jetty quiet unless something is really wrong.
        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");
        System.exit(new CommandLine(new VetDb()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    // ------------------------------------------------------------------ shared options

    static class DbOption {
        @Option(names = "--db", defaultValue = "out/vetdb.sqlite", description = "SQLite database file (default: ${DEFAULT-VALUE})")
        Path db;
    }

    static class CacheOption {
        @Option(names = "--cache", defaultValue = "cache", description = "Folder holding the cached HTTP responses (default: ${DEFAULT-VALUE})")
        Path cache;
    }

    static class AgentOption {
        @Option(names = "--user-agent", defaultValue = DEFAULT_USER_AGENT,
            description = "User-Agent header sent with every request (default: ${DEFAULT-VALUE})")
        String userAgent;
    }

    static class OutOption {
        @Option(names = "--out", defaultValue = "out", description = "Folder for the CSV and XLSX files (default: ${DEFAULT-VALUE})")
        Path out;
    }

    static class SourceOptions {
        @Option(names = "--source", defaultValue = "all", split = ",", paramLabel = "<name>",
            description = "Sources to run: all, or a comma separated list of ratemyvet, bestforpet, pia, vetnet (default: ${DEFAULT-VALUE})")
        List<String> sources;

        @Option(names = "--limit", defaultValue = "0", paramLabel = "<n>",
            description = "Only the first N listings of each source, 0 means all (default: ${DEFAULT-VALUE})")
        int limit;
    }

    static List<Source> allSources() {
        return List.of(new RateMyVetSource(), new BestForPetSource(), new PiaSource(), new VetNetSource());
    }

    // ------------------------------------------------------------------ commands

    @Command(name = "fetch", mixinStandardHelpOptions = true,
        description = "Download the listings (cached on disk) and store the cleaned records in the database.")
    static class Fetch implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin CacheOption cache;
        @Mixin AgentOption agent;
        @Mixin SourceOptions select;

        @Option(names = "--refresh", description = "Ignore the cache and download every page again")
        boolean refresh;

        @Override
        public Integer call() throws Exception {
            return scrape(db.db, cache.cache, agent.userAgent, select.sources, select.limit, refresh, false);
        }
    }

    @Command(name = "parse", mixinStandardHelpOptions = true,
        description = "Re-parse the cached pages without touching the network (use it after changing a parser).")
    static class Parse implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin CacheOption cache;
        @Mixin SourceOptions select;

        @Override
        public Integer call() throws Exception {
            return scrape(db.db, cache.cache, DEFAULT_USER_AGENT, select.sources, select.limit, false, true);
        }
    }

    @Command(name = "enrich-abr", mixinStandardHelpOptions = true, footer = ABR_FOOTER,
        description = "Look the clinics up in ABN Lookup: ABN, registered name, ABN status and date.")
    static class EnrichAbr implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin CacheOption cache;
        @Mixin AgentOption agent;

        @Option(names = "--limit", defaultValue = "0", paramLabel = "<n>",
            description = "Look up at most N clinics in this run, 0 means all (default: ${DEFAULT-VALUE})")
        int limit;

        @Option(names = "--refresh", description = "Look up again the clinics already done, ignoring the cache")
        boolean refresh;

        @Override
        public Integer call() throws Exception {
            return enrichAbr(db.db, cache.cache, agent.userAgent, limit, refresh);
        }
    }

    @Command(name = "enrich-google", mixinStandardHelpOptions = true, footer = GOOGLE_FOOTER,
        description = "Look the clinics up in Google Places: Maps link, rating, review count, open or closed. Paid per call.")
    static class EnrichGoogle implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin CacheOption cache;
        @Mixin AgentOption agent;

        @Option(names = "--budget", defaultValue = "100", paramLabel = "<n>",
            description = "Stop after N paid calls; cached answers are free (default: ${DEFAULT-VALUE})")
        int budget;

        @Option(names = "--refresh", description = "Look up again the clinics already done, ignoring the cache (costs money)")
        boolean refresh;

        @Override
        public Integer call() throws Exception {
            return enrichGoogle(db.db, cache.cache, agent.userAgent, budget, refresh);
        }
    }

    @Command(name = "merge", mixinStandardHelpOptions = true,
        description = "Dedupe the stored records into clinics and print a summary (no files written).")
    static class Merge implements Callable<Integer> {
        @Mixin DbOption db;

        @Override
        public Integer call() throws Exception {
            try (Db store = new Db(db.db)) {
                mergeAndSave(store);
            }
            return 0;
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
        description = "Merge, then write clinics.csv, needs_review.csv, rejected.csv, source_records.csv and clinics.xlsx.")
    static class Export implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin OutOption out;

        @Override
        public Integer call() throws Exception {
            return export(db.db, out.out);
        }
    }

    @Command(name = "all", mixinStandardHelpOptions = true, footer = BOTH_FOOTER,
        description = "Run every step: fetch, enrich-abr, enrich-google, then export.")
    static class All implements Callable<Integer> {
        @Mixin DbOption db;
        @Mixin CacheOption cache;
        @Mixin AgentOption agent;
        @Mixin OutOption out;
        @Mixin SourceOptions select;

        @Option(names = "--budget", defaultValue = "100", paramLabel = "<n>",
            description = "Cap on paid Google calls (default: ${DEFAULT-VALUE})")
        int budget;

        @Override
        public Integer call() throws Exception {
            int code = scrape(db.db, cache.cache, agent.userAgent, select.sources, select.limit, false, false);
            if (code != 0) {
                return code;
            }
            enrichAbr(db.db, cache.cache, agent.userAgent, 0, false);
            enrichGoogle(db.db, cache.cache, agent.userAgent, budget, false);
            return export(db.db, out.out);
        }
    }

    // ------------------------------------------------------------------ steps

    /** Runs the selected sources side by side (one thread each, they hit different hosts). */
    static int scrape(Path dbFile, Path cacheDir, String userAgent, List<String> names, int limit,
                      boolean refresh, boolean offline) throws Exception {
        List<Source> selected = allSources().stream()
            .filter(s -> names.contains("all") || names.contains(s.name()))
            .toList();
        if (selected.isEmpty()) {
            System.err.println("No source matches " + names + "; use all, ratemyvet, bestforpet, pia or vetnet");
            return 2;
        }
        Map<String, List<SourceRecord>> results = new ConcurrentHashMap<>();
        try (Fetcher fetcher = new Fetcher(cacheDir, offline, refresh, userAgent)) {
            selected.forEach(s -> fetcher.delay(s.host(), s.delayMillis()));
            List<Thread> threads = new ArrayList<>();
            for (Source s : selected) {
                threads.add(Thread.ofVirtual().name(s.name()).start(() -> {
                    List<SourceRecord> records = new ArrayList<>();
                    try {
                        s.run(fetcher, limit, records::add);
                    } catch (RuntimeException e) {
                        System.err.printf("[%s] stopped: %s%n", s.name(), e);
                    }
                    records.forEach(Normalizer::apply);
                    results.put(s.name(), records);
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
            System.out.printf("%d requests went to the network%n", fetcher.networkCalls());
        }
        try (Db db = new Db(dbFile)) {
            for (Source s : selected) {
                List<SourceRecord> records = results.getOrDefault(s.name(), List.of());
                if (records.isEmpty()) {
                    System.out.printf("[%s] no records, keeping what the database already has%n", s.name());
                    continue;
                }
                db.replaceSourceRecords(s.name(), records);
                System.out.printf("[%s] stored %d records%n", s.name(), records.size());
            }
        }
        return 0;
    }

    static List<Clinic> mergeAndSave(Db db) throws Exception {
        List<SourceRecord> records = db.loadSourceRecords();
        List<Clinic> clinics = Merger.merge(records, db.loadEnrichment());
        db.saveClinics(clinics);
        printSummary(records, clinics);
        return clinics;
    }

    static int export(Path dbFile, Path outDir) throws Exception {
        try (Db db = new Db(dbFile)) {
            List<Clinic> clinics = mergeAndSave(db);
            Exporter.Summary s = Exporter.export(outDir, clinics, db.loadSourceRecords());
            System.out.printf("Wrote %s: %d clinics, %d to review, %d rejected%n",
                outDir.resolve("clinics.xlsx"), s.clinics(), s.needsReview(), s.rejected());
        }
        return 0;
    }

    static int enrichAbr(Path dbFile, Path cacheDir, String userAgent, int limit, boolean refresh) throws Exception {
        String guid = System.getenv("ABR_GUID");
        if (guid == null || guid.isBlank()) {
            System.out.println("ABR_GUID is not set, skipping ABN lookups. "
                + "Register for free at https://abr.business.gov.au/Tools/WebServices");
            return 0;
        }
        try (Fetcher fetcher = new Fetcher(cacheDir, false, refresh, userAgent).delay(AbrClient.HOST, 500);
             Db db = new Db(dbFile)) {
            Map<String, Map<String, Enrichment>> enrichment = db.loadEnrichment();
            Map<String, Enrichment> done = enrichment.getOrDefault("abr", Map.of());
            AbrClient abr = new AbrClient(fetcher, guid);
            int looked = 0;
            Map<String, Integer> outcomes = new TreeMap<>();
            for (Clinic c : Merger.merge(db.loadSourceRecords(), enrichment)) {
                if (c.verification() == Clinic.Verification.REJECTED || (!refresh && alreadyDone(c, done))) {
                    continue;
                }
                if (limit > 0 && looked >= limit) {
                    break;
                }
                Enrichment e = abr.lookup(c);
                if (!"ERROR".equals(e.status())) {
                    db.saveEnrichment(e);
                }
                outcomes.merge(e.status(), 1, Integer::sum);
                if (++looked % 100 == 0) {
                    System.out.printf("[abr] %d looked up %s%n", looked, outcomes);
                }
            }
            System.out.printf("[abr] %d looked up %s%n", looked, outcomes);
        }
        return 0;
    }

    static int enrichGoogle(Path dbFile, Path cacheDir, String userAgent, int budget, boolean refresh) throws Exception {
        String key = System.getenv("GOOGLE_PLACES_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("GOOGLE_PLACES_API_KEY is not set, skipping Google Places lookups.");
            return 0;
        }
        try (Fetcher fetcher = new Fetcher(cacheDir, false, refresh, userAgent).delay(GooglePlacesClient.HOST, 100);
             Db db = new Db(dbFile)) {
            Map<String, Map<String, Enrichment>> enrichment = db.loadEnrichment();
            Map<String, Enrichment> done = enrichment.getOrDefault("google", Map.of());
            GooglePlacesClient google = new GooglePlacesClient(fetcher, key);
            Map<String, Integer> outcomes = new TreeMap<>();
            for (Clinic c : Merger.merge(db.loadSourceRecords(), enrichment)) {
                if (c.verification() == Clinic.Verification.REJECTED || (!refresh && alreadyDone(c, done))) {
                    continue;
                }
                if (google.paidCalls() >= budget) {
                    System.out.printf("[google] budget of %d paid calls reached, stopping%n", budget);
                    break;
                }
                Enrichment e = google.lookup(c);
                if (e != null) {
                    db.saveEnrichment(e);
                    outcomes.merge(e.status(), 1, Integer::sum);
                }
            }
            System.out.printf("[google] %d paid calls %s%n", google.paidCalls(), outcomes);
        }
        return 0;
    }

    private static boolean alreadyDone(Clinic c, Map<String, Enrichment> done) {
        return c.members().stream().anyMatch(m -> done.containsKey(m.key()));
    }

    static void printSummary(List<SourceRecord> records, List<Clinic> clinics) {
        Map<String, Long> perSource = records.stream()
            .collect(Collectors.groupingBy(SourceRecord::source, TreeMap::new, Collectors.counting()));
        Map<Clinic.Verification, Long> perStatus = clinics.stream()
            .collect(Collectors.groupingBy(Clinic::verification, TreeMap::new, Collectors.counting()));
        Map<Long, Long> perSourceCount = clinics.stream()
            .collect(Collectors.groupingBy(Clinic::directoryCount, TreeMap::new, Collectors.counting()));
        Map<String, Long> rejected = clinics.stream().filter(c -> c.rejectReason() != null)
            .collect(Collectors.groupingBy(Clinic::rejectReason, TreeMap::new, Collectors.counting()));
        Map<String, Long> flags = clinics.stream()
            .flatMap(c -> c.flags().stream().map(f -> f.replaceFirst("_AS_\\d+$", "")))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Map<String, Long> topFlags = flags.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
            .limit(12)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));

        System.out.println("Records per source:     " + perSource);
        System.out.println("Clinics:                " + clinics.size() + " " + perStatus);
        System.out.println("Clinics by # of sources: " + perSourceCount);
        System.out.println("Rejected because:       " + rejected);
        System.out.println("Most common flags:      " + topFlags);
    }
}
