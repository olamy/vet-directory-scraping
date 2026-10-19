package vetdb.model;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One clinic listing as found on one source site.
 * Values are plain strings; a missing value is simply absent, never an empty string.
 */
public final class SourceRecord {

    private final String source;
    private final String sourceId;
    private final String profileUrl;
    private String sourceModified;
    private String country;
    private final EnumMap<Field, String> values = new EnumMap<>(Field.class);
    private final Map<String, String> raw = new LinkedHashMap<>();
    private final Set<String> flags = new LinkedHashSet<>();

    public SourceRecord(String source, String sourceId, String profileUrl) {
        this.source = source;
        this.sourceId = sourceId;
        this.profileUrl = profileUrl;
    }

    public String source() {
        return source;
    }

    public String sourceId() {
        return sourceId;
    }

    /** Stable key such as "ratemyvet:4-paws-vet-neutral-bay-neutral-bay". */
    public String key() {
        return source + ":" + sourceId;
    }

    public String profileUrl() {
        return profileUrl;
    }

    public String sourceModified() {
        return sourceModified;
    }

    public void sourceModified(String sourceModified) {
        this.sourceModified = sourceModified;
    }

    /** Country as given by the source, if it gives one. */
    public String country() {
        return country;
    }

    public void country(String country) {
        this.country = country;
    }

    public String get(Field field) {
        return values.get(field);
    }

    public boolean has(Field field) {
        return values.containsKey(field);
    }

    /** Sets a value, or clears it when the value is null or blank. */
    public SourceRecord set(Field field, String value) {
        if (value == null || value.isBlank()) {
            values.remove(field);
        } else {
            values.put(field, value.strip());
        }
        return this;
    }

    public SourceRecord set(Field field, Number value) {
        return set(field, value == null ? null : value.toString());
    }

    public Map<Field, String> values() {
        return values;
    }

    /** Values exactly as scraped, before any cleaning. Kept for auditing. */
    public Map<String, String> raw() {
        return raw;
    }

    public Set<String> flags() {
        return flags;
    }

    public void flag(String code) {
        flags.add(code);
    }

    public Double latitude() {
        return parseDouble(get(Field.LATITUDE));
    }

    public Double longitude() {
        return parseDouble(get(Field.LONGITUDE));
    }

    private static Double parseDouble(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return key() + " " + values;
    }
}
