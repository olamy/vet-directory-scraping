package vetdb.enrich;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vetdb.clean.Normalizer;
import vetdb.http.Fetcher;
import vetdb.merge.MatchKeys;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.Field;

/**
 * Google Places API (New) text search. Needs GOOGLE_PLACES_API_KEY and is billed per call
 * (rating, review count and website put it in the Enterprise tier), so calls are cached
 * and capped by a budget.
 */
public final class GooglePlacesClient {

    public static final String HOST = "places.googleapis.com";
    private static final String URL = "https://places.googleapis.com/v1/places:searchText";
    private static final String FIELD_MASK = String.join(",",
        "places.id", "places.displayName", "places.formattedAddress", "places.location", "places.googleMapsUri",
        "places.businessStatus", "places.rating", "places.userRatingCount", "places.websiteUri",
        "places.nationalPhoneNumber");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Fetcher fetcher;
    private final String apiKey;
    private int paidCalls;

    public GooglePlacesClient(Fetcher fetcher, String apiKey) {
        this.fetcher = fetcher;
        this.apiKey = apiKey;
    }

    /** Calls that actually went to Google (cached answers are free). */
    public int paidCalls() {
        return paidCalls;
    }

    /** Returns null when the call failed, so it can be tried again on the next run. */
    public Enrichment lookup(Clinic c) {
        String key = c.anchor().key();
        Fetcher.Response r = fetcher.fetch("POST", URL, requestBody(c), Map.of(
            "X-Goog-Api-Key", apiKey,
            "X-Goog-FieldMask", FIELD_MASK,
            "Content-Type", "application/json"));
        if (!r.fromCache()) {
            paidCalls++;
        }
        if (!r.ok()) {
            System.err.printf("  google: HTTP %d for %s%n", r.status(), c.get(Field.NAME));
            return null;
        }
        try {
            JsonNode place = pickPlace(JSON.readTree(r.body()).path("places"), c);
            if (place == null) {
                return new Enrichment("google", key, "NOT_FOUND", Map.of());
            }
            return new Enrichment("google", key, "MATCHED", placeToValues(place));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    String requestBody(Clinic c) {
        StringJoiner query = new StringJoiner(", ");
        for (Field f : new Field[] {Field.NAME, Field.STREET, Field.SUBURB}) {
            if (c.get(f) != null) {
                query.add(c.get(f));
            }
        }
        String tail = ((c.get(Field.STATE) == null ? "" : c.get(Field.STATE)) + " "
            + (c.get(Field.POSTCODE) == null ? "" : c.get(Field.POSTCODE))).strip();
        if (!tail.isEmpty()) {
            query.add(tail);
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("textQuery", query.toString());
        body.put("includedType", "veterinary_care");
        body.put("regionCode", "AU");
        body.put("pageSize", 3);
        if (c.get(Field.LATITUDE) != null && c.get(Field.LONGITUDE) != null) {
            ObjectNode circle = body.putObject("locationBias").putObject("circle");
            circle.putObject("center")
                .put("latitude", Double.parseDouble(c.get(Field.LATITUDE)))
                .put("longitude", Double.parseDouble(c.get(Field.LONGITUDE)));
            circle.put("radius", 500.0);
        }
        return body.toString();
    }

    /** A result counts when it is close with a similar name, or shares the phone number. */
    static JsonNode pickPlace(JsonNode places, Clinic c) {
        String phone = MatchKeys.phoneKey(c.get(Field.PHONE));
        for (JsonNode p : places) {
            double similarity = MatchKeys.nameSimilarity(c.get(Field.NAME), p.path("displayName").path("text").asText(""));
            boolean samePhone = phone != null && phone.equals(MatchKeys.phoneKey(p.path("nationalPhoneNumber").asText(null)));
            Double metres = null;
            if (c.get(Field.LATITUDE) != null && p.path("location").has("latitude")) {
                metres = MatchKeys.distance(Double.parseDouble(c.get(Field.LATITUDE)), Double.parseDouble(c.get(Field.LONGITUDE)),
                    p.path("location").path("latitude").asDouble(), p.path("location").path("longitude").asDouble());
            }
            boolean samePostcode = c.get(Field.POSTCODE) != null
                && p.path("formattedAddress").asText("").contains(c.get(Field.POSTCODE));
            if (samePhone
                || (metres != null && metres <= 150 && similarity >= 0.7)
                || (metres == null && samePostcode && similarity >= 0.8)) {
                return p;
            }
        }
        return null;
    }

    static Map<Field, String> placeToValues(JsonNode p) {
        Map<Field, String> values = new EnumMap<>(Field.class);
        put(values, Field.GOOGLE_MAPS_URL, p.path("googleMapsUri").asText(null));
        put(values, Field.GOOGLE_BUSINESS_STATUS, p.path("businessStatus").asText(null));
        put(values, Field.GOOGLE_RATING, p.has("rating") ? String.format(Locale.ROOT, "%.1f", p.path("rating").asDouble()) : null);
        put(values, Field.GOOGLE_REVIEW_COUNT, p.has("userRatingCount") ? p.path("userRatingCount").asText() : null);
        put(values, Field.WEBSITE, Normalizer.url(p.path("websiteUri").asText(null)));
        Normalizer.Phone phone = Normalizer.phone(p.path("nationalPhoneNumber").asText(null));
        put(values, Field.PHONE, phone == null ? null : phone.national());
        return values;
    }

    private static void put(Map<Field, String> values, Field f, String v) {
        if (v != null && !v.isBlank()) {
            values.put(f, v);
        }
    }
}
