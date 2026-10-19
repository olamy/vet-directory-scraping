package vetdb.merge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import vetdb.clean.Normalizer;

/** Keys and similarity scores used to decide whether two listings are the same clinic. */
public final class MatchKeys {

    /** Words every vet name has; they say nothing about which clinic it is. */
    private static final Set<String> GENERIC = Set.of(
        "vet", "vets", "veterinary", "veterinarian", "veterinarians", "hospital", "clinic", "clinics",
        "centre", "center", "surgery", "practice", "animal", "animals", "pet", "pets", "care", "and",
        "the", "of", "pty", "ltd", "limited", "service", "services", "group", "co");

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    private MatchKeys() {
    }

    /** "4 Paws Veterinary Clinic Pty Ltd" becomes "4 paws". */
    public static String nameKey(String name) {
        if (name == null) {
            return "";
        }
        String plain = name.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9]+", " ").strip();
        String key = Arrays.stream(plain.split(" "))
            .filter(t -> !t.isEmpty() && !GENERIC.contains(t))
            .collect(Collectors.joining(" "));
        return key.isEmpty() ? plain : key;
    }

    /**
     * Score between 0 and 1. Takes the best of Jaro-Winkler on the name keys and
     * token containment, so "4 Paws Vet Neutral Bay" still matches "4 Paws Veterinary Clinic".
     */
    public static double nameSimilarity(String a, String b) {
        String ka = nameKey(a);
        String kb = nameKey(b);
        if (ka.isEmpty() || kb.isEmpty()) {
            return 0;
        }
        double jw = JARO_WINKLER.apply(ka, kb);
        Set<String> ta = new HashSet<>(Arrays.asList(ka.split(" ")));
        Set<String> tb = new HashSet<>(Arrays.asList(kb.split(" ")));
        int smaller = Math.min(ta.size(), tb.size());
        double containment = 0;
        // A single shared word ("bay", "city") is too weak on its own.
        if (smaller >= 2) {
            Set<String> common = new HashSet<>(ta);
            common.retainAll(tb);
            containment = (double) common.size() / smaller;
        }
        return Math.max(jw, containment);
    }

    /**
     * Street number, street name and postcode, without unit or shop numbers:
     * "Shop 5, 476 Gardeners Road" and "5/476 Gardeners Rd" both give "476 gardeners rd|2015".
     */
    public static String addressKey(String street, String postcode) {
        if (street == null || postcode == null) {
            return null;
        }
        String s = street.toLowerCase(Locale.ROOT).replaceAll("[.,']", " ").replaceAll("\\s+", " ").strip();
        s = s.replaceAll("^(?:(?:shop|unit|suite|ste|level|lvl|lot|tenancy|kiosk|u|t|g)\\s*[0-9a-z]+\\s*[/]?\\s*)+(?=\\d)", "");
        s = s.replaceAll("^[0-9a-z]+/(?=\\d)", "");
        s = s.replaceAll("^(\\d+[a-z]?)\\s*-\\s*\\d+[a-z]?\\b", "$1");
        String key = Arrays.stream(s.split(" "))
            .map(w -> {
                String abbr = Normalizer.streetTypeAbbreviation(w);
                return abbr == null ? w : abbr.toLowerCase(Locale.ROOT);
            })
            .collect(Collectors.joining(" "));
        return key.isEmpty() ? null : key + "|" + postcode;
    }

    /** E.164 phone, or null when missing or a chain-wide 13/1300/1800 number. */
    public static String phoneKey(String phone) {
        Normalizer.Phone p = Normalizer.phone(phone);
        if (p == null || Normalizer.isSharedNumber(p.e164())) {
            return null;
        }
        return p.e164();
    }

    /** Distance in metres between two points (haversine). */
    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
