package vetdb.enrich;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
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
    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();
    /** Words in registered names that are about the legal structure, not the business. */
    private static final Set<String> LEGAL_WORDS = Set.of("pty", "ltd", "limited", "inc", "incorporated", "the",
        "trustee", "trustees", "for", "trust", "unit", "atf");
    /** Besides anything with "vet" in it, the words that say a business is about animals. */
    private static final Set<String> ANIMAL_WORDS = Set.of("animal", "animals", "pet", "pets", "equine", "paws", "wildlife");

    private final Fetcher fetcher;
    private final String guid;

    public AbrClient(Fetcher fetcher, String guid) {
        this.fetcher = fetcher;
        this.guid = guid;
    }

    /** What a lookup found, plus the names and ABNs that tied when it is AMBIGUOUS. */
    public record Result(Enrichment enrichment, List<String> candidates) {
    }

    /** The ABN picked from a name search, or the candidates that tie for it. Both are empty when nothing fits. */
    record Pick(String abn, List<String> tied) {
    }

    /** Searches the clinic name, keeps candidates in the same postcode (or state), then reads the ABN details. */
    public Enrichment lookup(Clinic c) {
        return lookup(c.anchor().key(), c.get(Field.NAME), c.get(Field.POSTCODE), c.get(Field.STATE)).enrichment();
    }

    /** Same lookup from plain values, for when there is no merged clinic (a CSV row for example). */
    public Result lookup(String key, String name, String postcode, String state) {
        if (name == null) {
            return empty(key, "NOT_FOUND");
        }
        String search = fetcher.get(BASE + "MatchingNames.aspx?name=" + encode(name)
            + "&maxResults=20&callback=c&guid=" + encode(guid));
        JsonNode result = unwrap(search);
        if (result == null) {
            return empty(key, "ERROR");
        }
        Pick pick = pickAbn(result.path("Names"), name, postcode, state);
        if (!pick.tied().isEmpty()) {
            return new Result(new Enrichment("abr", key, "AMBIGUOUS", Map.of()), pick.tied());
        }
        if (pick.abn() == null) {
            return empty(key, "NOT_FOUND");
        }
        JsonNode details = unwrap(fetcher.get(BASE + "AbnDetails.aspx?abn=" + pick.abn() + "&callback=c&guid=" + encode(guid)));
        if (details == null) {
            return empty(key, "ERROR");
        }
        return new Result(new Enrichment("abr", key, "MATCHED", detailsToValues(details)), List.of());
    }

    private static Result empty(String key, String status) {
        return new Result(new Enrichment("abr", key, status, Map.of()), List.of());
    }

    /**
     * Best ABN among the search results.
     * A candidate must sit in the clinic's postcode, or in its state with a near perfect name, and can't be
     * another trade sharing the clinic's place name ("Albion Park Rail Florist" for "Albion Park Rail Vet").
     * When names score the same, an active ABN wins over a cancelled one (a clinic that changed hands keeps
     * its name on the old ABN too), then the closest full name, then Business and Entity names over old
     * Trading names. What still ties after that comes back in {@code tied}.
     */
    static Pick pickAbn(JsonNode names, String clinicName, String postcode, String state) {
        record Candidate(String abn, String name, boolean active, double score, double fullName, int nameType) {
        }
        Set<String> clinicWords = words(clinicName);
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode n : names) {
            String abn = n.path("Abn").asText(null);
            String name = n.path("Name").asText("");
            if (abn == null || abn.isBlank() || otherTrade(name, clinicWords)) {
                continue;
            }
            // Without "The Trustee for ... Unit Trust" around it, a trust's name compares like any other.
            double score = Math.max(n.path("Score").asDouble(0) / 100.0, MatchKeys.nameSimilarity(clinicName, plain(name)));
            boolean samePostcode = postcode != null && postcode.equals(n.path("Postcode").asText());
            boolean sameState = state != null && state.equalsIgnoreCase(n.path("State").asText());
            if ((samePostcode && score >= 0.90) || (sameState && score >= 0.98)) {
                candidates.add(new Candidate(abn, name, active(n), score,
                    JARO_WINKLER.apply(plain(clinicName), plain(name)), nameTypeRank(n.path("NameType").asText(""))));
            }
        }
        if (candidates.isEmpty()) {
            return new Pick(null, List.of());
        }
        Comparator<Candidate> best = Comparator.comparingDouble(Candidate::score).reversed()
            .thenComparing(Candidate::active, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingDouble(Candidate::fullName).reversed())
            .thenComparingInt(Candidate::nameType);
        candidates.sort(best);
        Map<String, String> tied = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (best.compare(c, candidates.getFirst()) == 0) {
                tied.putIfAbsent(c.abn(), c.name() + " (" + formatAbn(c.abn()) + ")");
            }
        }
        return tied.size() > 1 ? new Pick(null, List.copyOf(tied.values())) : new Pick(candidates.getFirst().abn(), List.of());
    }

    /** A current name on an active ABN. ABR sends 0000000001 for active and 0000000002 for cancelled. */
    private static boolean active(JsonNode n) {
        String status = n.path("AbnStatus").asText("");
        boolean active = status.isEmpty() || status.equals("0000000001") || status.equalsIgnoreCase("Active");
        return active && n.path("IsCurrent").asBoolean(true);
    }

    /** A name with distinctive words the clinic doesn't have, and nothing about animals: a florist, a newsagency... */
    static boolean otherTrade(String name, Set<String> clinicWords) {
        Set<String> own = words(name);
        own.removeAll(clinicWords);
        return !own.isEmpty() && Arrays.stream(plain(name).split(" ")).noneMatch(w -> w.contains("vet") || ANIMAL_WORDS.contains(w));
    }

    /** The words that tell one business from another: no vet words and no legal ones like "Pty Ltd" or "Trustee for". */
    static Set<String> words(String name) {
        Set<String> words = new LinkedHashSet<>(Arrays.asList(MatchKeys.nameKey(name).split(" ")));
        words.removeAll(LEGAL_WORDS);
        words.remove("");
        return words;
    }

    /** Lowercase name without the legal words, keeping the vet words: "LAKESIDE VET PTY LTD" becomes "lakeside vet". */
    private static String plain(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).replace("&", " and ").split("[^a-z0-9]+"))
            .filter(w -> !w.isEmpty() && !LEGAL_WORDS.contains(w))
            .collect(Collectors.joining(" "));
    }

    /** Business and entity names are kept up to date; trading names haven't been since 2012. */
    private static int nameTypeRank(String nameType) {
        return switch (nameType) {
            case "Business Name", "Entity Name" -> 0;
            case "Other Name" -> 1;
            default -> 2;
        };
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
