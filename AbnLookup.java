///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//SOURCES VetDb.java

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import vetdb.enrich.AbnCsv;
import vetdb.enrich.AbrClient;
import vetdb.http.Fetcher;

@Command(name = "abn-lookup", mixinStandardHelpOptions = true,
    description = {"Looks up the ABN of every clinic in a CSV file and adds it to each row.",
        "Rows are matched on their \"Clinic name\", \"Postcode\" and \"State\" columns. The columns added are"
            + " ABN, ABN registered name, ABN status, ABN match (MATCHED, NOT_FOUND, AMBIGUOUS or ERROR)"
            + " and ABN candidates (the names and ABNs that tied, for AMBIGUOUS rows)."},
    footer = "%nEnvironment:%n  ABR_GUID   free GUID from https://abr.business.gov.au/Tools/WebServices")
public class AbnLookup implements Callable<Integer> {

    @Parameters(paramLabel = "<csv>", description = "CSV file of clinics, updated in place unless --out is given")
    Path csv;

    @Option(names = {"-o", "--out"}, paramLabel = "<file>", description = "Write the result to this file instead")
    Path out;

    @Option(names = "--limit", defaultValue = "0", paramLabel = "<n>",
        description = "Look up only the first N rows, the others keep their ABN values, 0 means all (default: ${DEFAULT-VALUE})")
    int limit;

    @Option(names = "--refresh", description = "Ask ABN Lookup again instead of using the cached answers")
    boolean refresh;

    @Mixin VetDb.CacheOption cache;
    @Mixin VetDb.AgentOption agent;

    public static void main(String[] args) {
        // Keep Jetty quiet unless something is really wrong.
        System.setProperty("org.eclipse.jetty.LEVEL", "WARN");
        System.exit(new CommandLine(new AbnLookup()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        String guid = System.getenv("ABR_GUID");
        if (guid == null || guid.isBlank()) {
            System.err.println("ABR_GUID is not set. Register for free at https://abr.business.gov.au/Tools/WebServices");
            return 1;
        }
        Path target = out == null ? csv : out;
        try (Fetcher fetcher = new Fetcher(cache.cache, false, refresh, agent.userAgent).delay(AbrClient.HOST, 500)) {
            AbrClient abr = new AbrClient(fetcher, guid);
            Map<String, Integer> outcomes = AbnCsv.enrich(csv, target, abr::lookup, limit);
            System.out.printf("[abn] wrote %s %s (%d calls to ABN Lookup)%n", target, outcomes, fetcher.networkCalls());
        }
        return 0;
    }
}
