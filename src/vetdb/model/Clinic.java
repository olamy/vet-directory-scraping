package vetdb.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One real-world clinic, merged from one or more source records. */
public final class Clinic {

    public enum Verification { VERIFIED, SINGLE_SOURCE, REJECTED }

    private int id;
    private final List<SourceRecord> members = new ArrayList<>();
    private final EnumMap<Field, String> values = new EnumMap<>(Field.class);
    private final Map<Field, String> provenance = new EnumMap<>(Field.class);
    private final Map<String, String> matchRules = new LinkedHashMap<>();
    private final Set<String> flags = new LinkedHashSet<>();
    private Verification verification = Verification.SINGLE_SOURCE;
    private String rejectReason;

    public int id() {
        return id;
    }

    public void id(int id) {
        this.id = id;
    }

    public List<SourceRecord> members() {
        return members;
    }

    /** The member whose key identifies this clinic across runs (used by the enrichment tables). */
    public SourceRecord anchor() {
        return members.getFirst();
    }

    public String get(Field field) {
        return values.get(field);
    }

    public void set(Field field, String value, String from) {
        if (value == null || value.isBlank()) {
            values.remove(field);
            provenance.remove(field);
        } else {
            values.put(field, value);
            provenance.put(field, from);
        }
    }

    public Map<Field, String> values() {
        return values;
    }

    public Map<Field, String> provenance() {
        return provenance;
    }

    /** How each member joined the cluster, by member key. */
    public Map<String, String> matchRules() {
        return matchRules;
    }

    public Set<String> flags() {
        return flags;
    }

    public Verification verification() {
        return verification;
    }

    public void verification(Verification verification) {
        this.verification = verification;
    }

    public String rejectReason() {
        return rejectReason;
    }

    public void reject(String reason) {
        this.verification = Verification.REJECTED;
        this.rejectReason = reason;
    }

    /** Profile URL on the given source, or null when the clinic isn't listed there. */
    public String profileUrl(String source) {
        return members.stream()
            .filter(m -> m.source().equals(source))
            .map(SourceRecord::profileUrl)
            .findFirst()
            .orElse(null);
    }

    /** Distinct source names, in member order. */
    public List<String> sources() {
        return members.stream().map(SourceRecord::source).distinct().toList();
    }

    /** Distinct directory sources (the four scraped sites). */
    public long directoryCount() {
        return sources().size();
    }
}
