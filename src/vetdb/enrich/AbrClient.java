package vetdb.enrich;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vetdb.http.Fetcher;
import vetdb.merge.MatchKeys;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.Field;

/**
 * ABN Lookup (abr.business.gov.au) JSON web services. Needs a free GUID from
 * https://abr.business.gov.au/Tools/WebServices, passed in the ABR_GUID environment variable.
 */
public final class AbrClient {

    public static final String HOST = "abr.business.gov.au";
    private static final String BASE = "https://abr.business.gov.au/json/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Fetcher fetcher;
    private final String guid;

    public AbrClient(Fetcher fetcher, String guid) {
        this.fetcher = fetcher;
        this.guid = guid;
    }

    /** Searches the clinic name, keeps candidates in the same postcode (or state), then reads the ABN details. */
    public Enrichment lookup(Clinic c) {
        String key = c.anchor().key();
        String name = c.get(Field.NAME);
        if (name == null) {
            return new Enrichment("abr", key, "NOT_FOUND", Map.of());
        }
        String search = fetcher.get(BASE + "MatchingNames.aspx?name=" + encode(name)
            + "&maxResults=20&callback=c&guid=" + encode(guid));
        JsonNode result = unwrap(search);
        if (result == null) {
            return new Enrichment("abr", key, "ERROR", Map.of());
        }
        String abn = pickAbn(result.path("Names"), name, c.get(Field.POSTCODE), c.get(Field.STATE));
        if (abn == null) {
            return new Enrichment("abr", key, "NOT_FOUND", Map.of());
        }
        if (abn.equals("AMBIGUOUS")) {
            return new Enrichment("abr", key, "AMBIGUOUS", Map.of());
        }
        JsonNode details = unwrap(fetcher.get(BASE + "AbnDetails.aspx?abn=" + abn + "&callback=c&guid=" + encode(guid)));
        if (details == null) {
            return new Enrichment("abr", key, "ERROR", Map.of());
        }
        return new Enrichment("abr", key, "MATCHED", detailsToValues(details));
    }

    /**
     * Best ABN among the search results, "AMBIGUOUS" when two different ABNs tie, or null.
     * A candidate must sit in the clinic's postcode, or in its state with a near perfect name.
     */
    static String pickAbn(JsonNode names, String clinicName, String postcode, String state) {
        record Candidate(String abn, double score) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode n : names) {
            String abn = n.path("Abn").asText(null);
            if (abn == null || abn.isBlank()) {
                continue;
            }
            double abrScore = n.path("Score").asDouble(0);
            double ours = MatchKeys.nameSimilarity(clinicName, n.path("Name").asText(""));
            double score = Math.max(abrScore / 100.0, ours);
            boolean samePostcode = postcode != null && postcode.equals(n.path("Postcode").asText());
            boolean sameState = state != null && state.equalsIgnoreCase(n.path("State").asText());
            if ((samePostcode && score >= 0.90) || (sameState && score >= 0.98)) {
                candidates.add(new Candidate(abn, score));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        Candidate best = candidates.getFirst();
        boolean tie = candidates.stream().anyMatch(o -> !o.abn().equals(best.abn()) && o.score() >= best.score());
        return tie ? "AMBIGUOUS" : best.abn();
    }

    static Map<Field, String> detailsToValues(JsonNode d) {
        Map<Field, String> values = new EnumMap<>(Field.class);
        values.put(Field.ABN, formatAbn(d.path("Abn").asText(null)));
        values.put(Field.REGISTERED_NAME, blankToNull(d.path("EntityName").asText(null)));
        String status = blankToNull(d.path("AbnStatus").asText(null));
        values.put(Field.ABN_STATUS, status);
        // The effective date of an "Active" status is when the ABN was (re)activated.
        if ("Active".equalsIgnoreCase(status)) {
            values.put(Field.ABN_REGISTERED_FROM, blankToNull(d.path("AbnStatusEffectiveFrom").asText(null)));
        }
        values.values().removeIf(v -> v == null);
        return values;
    }

    /** ABN in the usual "12 345 678 901" layout. */
    static String formatAbn(String abn) {
        if (abn == null) {
            return null;
        }
        String digits = abn.replaceAll("\\D", "");
        if (digits.length() != 11) {
            return blankToNull(abn);
        }
        return digits.substring(0, 2) + " " + digits.substring(2, 5) + " " + digits.substring(5, 8) + " " + digits.substring(8);
    }

    /** The JSON services answer JSONP, e.g. c({...}); this strips the callback wrapper. */
    static JsonNode unwrap(String jsonp) {
        if (jsonp == null) {
            return null;
        }
        int start = jsonp.indexOf('(');
        int end = jsonp.lastIndexOf(')');
        String body = start >= 0 && end > start ? jsonp.substring(start + 1, end) : jsonp;
        try {
            JsonNode node = JSON.readTree(body);
            String message = node.path("Message").asText("");
            if (message.toLowerCase().contains("guid")) {
                throw new IllegalStateException("ABR refused the GUID: " + message);
            }
            return node;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
