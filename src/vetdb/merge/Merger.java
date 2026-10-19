package vetdb.merge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import vetdb.clean.Normalizer;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/**
 * Groups source records that describe the same clinic, then builds one merged row per group.
 *
 * Two records are the same clinic when:
 * 1. they share an address key and their names are similar (0.80 or more), or
 * 2. they share a phone number (not a 13/1300/1800 one) and the same postcode or are within 1 km, or
 * 3. they are within 75 m and their names are very similar (0.85 or more).
 * Records at the same address that fail all three stay apart and get a SAME_ADDRESS flag.
 */
public final class Merger {

    public static final List<String> SOURCE_ORDER = List.of("ratemyvet", "vetnet", "bestforpet", "pia");

    static final double SAME_ADDRESS_NAME = 0.80;
    static final double NEARBY_NAME = 0.85;
    static final double NEARBY_METRES = 75;
    static final double PHONE_METRES = 1000;

    /** Which source wins for a field. Fields not listed follow SOURCE_ORDER. */
    private static final Map<Field, List<String>> PRECEDENCE = new EnumMap<>(Field.class);

    private static final Pattern NOT_A_VET = Pattern.compile(
        "(?i).*\\b(pet shop|pet store|pet supplies|grooming|groomer|groomers|boarding|kennels?|cattery|rescue|adoption|insurance|daycare|dog training|aquarium|pet ?sitting)\\b.*");
    private static final Pattern VET_WORDS = Pattern.compile(
        "(?i).*\\b(vet|vets|veterinary|veterinarian|animal hospital|clinic|surgery|hospital|doctor|doctors)\\b.*");

    static {
        PRECEDENCE.put(Field.PHONE, List.of("ratemyvet", "bestforpet", "vetnet", "pia"));
        PRECEDENCE.put(Field.WEBSITE, List.of("bestforpet", "ratemyvet", "vetnet", "pia"));
        PRECEDENCE.put(Field.EMAIL, List.of("bestforpet", "pia", "vetnet", "ratemyvet"));
        PRECEDENCE.put(Field.NUMBER_OF_VETS, List.of("bestforpet", "vetnet"));
        PRECEDENCE.put(Field.NUMBER_OF_STAFF, List.of("bestforpet", "vetnet"));
        PRECEDENCE.put(Field.OPENING_HOURS, List.of("bestforpet", "vetnet", "ratemyvet"));
        PRECEDENCE.put(Field.CATEGORY, List.of("ratemyvet", "bestforpet", "pia"));
        PRECEDENCE.put(Field.FACEBOOK, List.of("vetnet", "pia", "bestforpet", "ratemyvet"));
        PRECEDENCE.put(Field.INSTAGRAM, List.of("vetnet", "pia", "bestforpet", "ratemyvet"));
        PRECEDENCE.put(Field.SPECIES_TREATED, List.of("ratemyvet", "bestforpet", "pia"));
    }

    private Merger() {
    }

    /**
     * @param records    normalised records from every source
     * @param enrichment ABR and Google results by kind, then by member key
     */
    public static List<Clinic> merge(List<SourceRecord> records, Map<String, Map<String, Enrichment>> enrichment) {
        int n = records.size();
        String[] names = new String[n];
        String[] addressKeys = new String[n];
        String[] phoneKeys = new String[n];
        for (int i = 0; i < n; i++) {
            SourceRecord r = records.get(i);
            names[i] = r.get(Field.NAME);
            addressKeys[i] = MatchKeys.addressKey(r.get(Field.STREET), r.get(Field.POSTCODE));
            phoneKeys[i] = MatchKeys.phoneKey(r.get(Field.PHONE));
        }
        dropPhonesSharedAcrossTowns(records, phoneKeys);

        UnionFind groups = new UnionFind(n);
        Map<Integer, String> joinedBy = new HashMap<>();
        List<int[]> sameAddress = new ArrayList<>();
        for (long pair : candidatePairs(records, phoneKeys)) {
            int a = (int) (pair >>> 32);
            int b = (int) pair;
            SourceRecord ra = records.get(a);
            SourceRecord rb = records.get(b);
            double similarity = MatchKeys.nameSimilarity(names[a], names[b]);
            boolean sameAddr = addressKeys[a] != null && addressKeys[a].equals(addressKeys[b]);
            boolean samePhone = phoneKeys[a] != null && phoneKeys[a].equals(phoneKeys[b]);
            Double metres = distance(ra, rb);
            boolean samePostcode = ra.get(Field.POSTCODE) != null && ra.get(Field.POSTCODE).equals(rb.get(Field.POSTCODE));

            String rule = null;
            if (sameAddr && similarity >= SAME_ADDRESS_NAME) {
                rule = "same_address";
            } else if (samePhone && (samePostcode || (metres != null && metres <= PHONE_METRES))) {
                rule = "same_phone";
            } else if (metres != null && metres <= NEARBY_METRES && similarity >= NEARBY_NAME) {
                rule = "nearby_same_name";
            } else if (sameAddr) {
                sameAddress.add(new int[] {a, b});
            }
            if (rule != null) {
                groups.union(a, b);
                joinedBy.putIfAbsent(a, rule);
                joinedBy.putIfAbsent(b, rule);
            }
        }

        Map<Integer, List<Integer>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(groups.find(i), k -> new ArrayList<>()).add(i);
        }

        List<Clinic> clinics = new ArrayList<>();
        Map<Integer, Clinic> clinicByRecord = new HashMap<>();
        Comparator<Integer> memberOrder = Comparator
            .comparingInt((Integer i) -> sourceRank(records.get(i).source()))
            .thenComparing(i -> records.get(i).sourceId());
        for (List<Integer> members : clusters.values()) {
            members.sort(memberOrder);
            Clinic c = new Clinic();
            for (int i : members) {
                SourceRecord r = records.get(i);
                c.members().add(r);
                c.matchRules().put(r.key(), members.size() == 1 ? "single" : joinedBy.getOrDefault(i, "single"));
                clinicByRecord.put(i, c);
            }
            build(c, enrichment);
            clinics.add(c);
        }

        clinics.sort(Comparator
            .comparing((Clinic c) -> nullLast(c.get(Field.STATE)))
            .thenComparing(c -> nullLast(c.get(Field.SUBURB)))
            .thenComparing(c -> nullLast(c.get(Field.NAME))));
        for (int i = 0; i < clinics.size(); i++) {
            clinics.get(i).id(i + 1);
        }
        for (int[] pair : sameAddress) {
            Clinic a = clinicByRecord.get(pair[0]);
            Clinic b = clinicByRecord.get(pair[1]);
            if (a != b) {
                a.flags().add("SAME_ADDRESS_AS_" + b.id());
                b.flags().add("SAME_ADDRESS_AS_" + a.id());
            }
        }
        return clinics;
    }

    /** A number used in three or more postcodes belongs to a chain's call centre, not to one clinic. */
    private static void dropPhonesSharedAcrossTowns(List<SourceRecord> records, String[] phoneKeys) {
        Map<String, Set<String>> postcodesByPhone = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            if (phoneKeys[i] != null && records.get(i).get(Field.POSTCODE) != null) {
                postcodesByPhone.computeIfAbsent(phoneKeys[i], k -> new HashSet<>()).add(records.get(i).get(Field.POSTCODE));
            }
        }
        for (int i = 0; i < phoneKeys.length; i++) {
            if (phoneKeys[i] != null && postcodesByPhone.getOrDefault(phoneKeys[i], Set.of()).size() >= 3) {
                phoneKeys[i] = null;
            }
        }
    }

    /** Pairs worth comparing: same postcode, same phone, or close on the map. Encoded as (a << 32 | b), a < b. */
    private static Set<Long> candidatePairs(List<SourceRecord> records, String[] phoneKeys) {
        Map<String, List<Integer>> buckets = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            SourceRecord r = records.get(i);
            if (r.get(Field.POSTCODE) != null) {
                buckets.computeIfAbsent("pc:" + r.get(Field.POSTCODE), k -> new ArrayList<>()).add(i);
            }
            if (phoneKeys[i] != null) {
                buckets.computeIfAbsent("ph:" + phoneKeys[i], k -> new ArrayList<>()).add(i);
            }
        }
        Set<Long> pairs = new HashSet<>();
        for (List<Integer> bucket : buckets.values()) {
            for (int x = 0; x < bucket.size(); x++) {
                for (int y = x + 1; y < bucket.size(); y++) {
                    pairs.add(pair(bucket.get(x), bucket.get(y)));
                }
            }
        }
        // Grid cells of 0.01 degree (about 1 km); compare each point with its 3x3 neighbourhood.
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < records.size(); i++) {
            Double lat = records.get(i).latitude();
            Double lng = records.get(i).longitude();
            if (lat != null && lng != null) {
                grid.computeIfAbsent(cell((long) Math.floor(lat * 100), (long) Math.floor(lng * 100)), k -> new ArrayList<>()).add(i);
            }
        }
        for (int i = 0; i < records.size(); i++) {
            Double lat = records.get(i).latitude();
            Double lng = records.get(i).longitude();
            if (lat == null || lng == null) {
                continue;
            }
            long cy = (long) Math.floor(lat * 100);
            long cx = (long) Math.floor(lng * 100);
            for (long dy = -1; dy <= 1; dy++) {
                for (long dx = -1; dx <= 1; dx++) {
                    for (int j : grid.getOrDefault(cell(cy + dy, cx + dx), List.of())) {
                        if (j != i) {
                            pairs.add(pair(i, j));
                        }
                    }
                }
            }
        }
        return pairs;
    }

    private static long cell(long y, long x) {
        return (y << 32) ^ (x & 0xffffffffL);
    }

    private static long pair(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    private static Double distance(SourceRecord a, SourceRecord b) {
        if (a.latitude() == null || b.latitude() == null || a.longitude() == null || b.longitude() == null) {
            return null;
        }
        return MatchKeys.distance(a.latitude(), a.longitude(), b.latitude(), b.longitude());
    }

    // ------------------------------------------------------------------ building one clinic

    static void build(Clinic c, Map<String, Map<String, Enrichment>> enrichment) {
        Enrichment google = find(c, enrichment.get("google"));
        Enrichment abr = find(c, enrichment.get("abr"));

        // The address fields come together from one member, so we never mix two addresses.
        SourceRecord addressFrom = c.members().stream()
            .filter(m -> m.has(Field.STREET) && m.has(Field.POSTCODE))
            .min(Comparator.comparingInt(m -> sourceRank(m.source())))
            .orElse(c.members().stream().filter(m -> m.has(Field.POSTCODE)).findFirst().orElse(c.anchor()));
        for (Field f : List.of(Field.STREET, Field.SUBURB, Field.STATE, Field.POSTCODE)) {
            c.set(f, addressFrom.get(f), addressFrom.source());
        }
        SourceRecord coordsFrom = addressFrom.has(Field.LATITUDE) ? addressFrom
            : c.members().stream().filter(m -> m.has(Field.LATITUDE)).findFirst().orElse(null);
        if (coordsFrom != null) {
            c.set(Field.LATITUDE, coordsFrom.get(Field.LATITUDE), coordsFrom.source());
            c.set(Field.LONGITUDE, coordsFrom.get(Field.LONGITUDE), coordsFrom.source());
        }

        for (Field f : Field.values()) {
            if (c.values().containsKey(f) || f == Field.LATITUDE || f == Field.LONGITUDE) {
                continue;
            }
            if (f.isYesNo()) {
                yesNo(c, f);
            } else if (f.isList()) {
                union(c, f);
            } else {
                firstByPrecedence(c, f);
            }
        }

        if (google != null && google.matched()) {
            for (Field f : List.of(Field.GOOGLE_RATING, Field.GOOGLE_REVIEW_COUNT, Field.GOOGLE_MAPS_URL, Field.GOOGLE_BUSINESS_STATUS)) {
                if (google.values().get(f) != null) {
                    c.set(f, google.values().get(f), "google");
                }
            }
            for (Field f : List.of(Field.PHONE, Field.WEBSITE)) {
                if (c.get(f) == null && google.values().get(f) != null) {
                    c.set(f, google.values().get(f), "google");
                }
            }
        }
        if (abr != null) {
            if (abr.matched()) {
                for (Field f : List.of(Field.ABN, Field.REGISTERED_NAME, Field.ABN_STATUS, Field.ABN_REGISTERED_FROM)) {
                    c.set(f, abr.values().get(f), "abr");
                }
                if ("Cancelled".equalsIgnoreCase(abr.values().get(Field.ABN_STATUS))) {
                    c.flags().add("ABN_CANCELLED");
                }
            } else if ("AMBIGUOUS".equals(abr.status())) {
                c.flags().add("ABN_AMBIGUOUS");
            }
        }

        for (SourceRecord m : c.members()) {
            m.flags().forEach(flag -> c.flags().add(m.source().toUpperCase(Locale.ROOT) + "_" + flag));
        }
        if (c.members().size() != c.sources().size()) {
            c.flags().add("SEVERAL_LISTINGS_SAME_SOURCE");
        }
        if (c.get(Field.STREET) == null) {
            c.flags().add("NO_STREET_ADDRESS");
        }
        if (!Normalizer.postcodeInState(c.get(Field.POSTCODE), c.get(Field.STATE))) {
            c.flags().add("POSTCODE_STATE_MISMATCH");
        }

        verify(c, google, abr);
    }

    private static void verify(Clinic c, Enrichment google, Enrichment abr) {
        for (SourceRecord m : c.members()) {
            String country = m.country();
            if (country != null && !country.equalsIgnoreCase("AU") && !country.equalsIgnoreCase("Australia")) {
                c.reject("NOT_IN_AUSTRALIA");
                return;
            }
        }
        if (c.get(Field.STATE) == null && c.get(Field.POSTCODE) == null && c.get(Field.LATITUDE) == null) {
            c.reject("NO_LOCATION");
            return;
        }
        String name = c.get(Field.NAME) == null ? "" : c.get(Field.NAME);
        String category = c.get(Field.CATEGORY) == null ? "" : c.get(Field.CATEGORY);
        if (NOT_A_VET.matcher(name).matches() && !VET_WORDS.matcher(name + " " + category).matches()) {
            c.reject("NOT_A_VET");
            return;
        }
        String googleStatus = c.get(Field.GOOGLE_BUSINESS_STATUS);
        if ("CLOSED_PERMANENTLY".equals(googleStatus)) {
            c.reject("CLOSED_PERMANENTLY");
            return;
        }
        if ("CLOSED_TEMPORARILY".equals(googleStatus)) {
            c.flags().add("CLOSED_TEMPORARILY");
        }

        boolean severalDirectories = c.directoryCount() >= 2;
        boolean googleOpen = "OPERATIONAL".equals(googleStatus);
        boolean activeAbn = abr != null && abr.matched() && "Active".equalsIgnoreCase(abr.values().get(Field.ABN_STATUS));
        c.verification(severalDirectories || googleOpen || activeAbn
            ? Clinic.Verification.VERIFIED : Clinic.Verification.SINGLE_SOURCE);
    }

    private static Enrichment find(Clinic c, Map<String, Enrichment> results) {
        if (results == null) {
            return null;
        }
        for (SourceRecord m : c.members()) {
            Enrichment e = results.get(m.key());
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static void firstByPrecedence(Clinic c, Field f) {
        for (String source : PRECEDENCE.getOrDefault(f, SOURCE_ORDER)) {
            for (SourceRecord m : c.members()) {
                if (m.source().equals(source) && m.get(f) != null) {
                    c.set(f, m.get(f), source);
                    return;
                }
            }
        }
    }

    /** Y if any source says yes, N if one says no and none says yes, blank otherwise. */
    private static void yesNo(Clinic c, Field f) {
        String no = null;
        for (SourceRecord m : c.members()) {
            if ("Y".equals(m.get(f))) {
                c.set(f, "Y", m.source());
                return;
            }
            if ("N".equals(m.get(f)) && no == null) {
                no = m.source();
            }
        }
        if (no != null) {
            c.set(f, "N", no);
        }
    }

    private static void union(Clinic c, Field f) {
        List<String> items = new ArrayList<>();
        List<String> from = new ArrayList<>();
        for (SourceRecord m : c.members()) {
            if (m.get(f) != null) {
                items.addAll(Normalizer.splitList(m.get(f)));
                if (!from.contains(m.source())) {
                    from.add(m.source());
                }
            }
        }
        c.set(f, Normalizer.list(items), String.join("+", from));
    }

    static int sourceRank(String source) {
        int i = SOURCE_ORDER.indexOf(source);
        return i < 0 ? SOURCE_ORDER.size() : i;
    }

    private static String nullLast(String s) {
        return s == null ? "￿" : s;
    }

    /** Plain union-find with path halving. */
    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int n) {
            parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
        }

        int find(int i) {
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                parent[Math.max(ra, rb)] = Math.min(ra, rb);
            }
        }
    }
}
