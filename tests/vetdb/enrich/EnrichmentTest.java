package vetdb.enrich;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vetdb.Fixtures;
import vetdb.clean.Normalizer;
import vetdb.merge.Merger;
import vetdb.model.Clinic;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses sample responses in the documented ABR and Google formats, since live calls need credentials. */
class EnrichmentTest {

    private static Clinic clinic() {
        SourceRecord r = new SourceRecord("ratemyvet", "lakeside", "https://ratemyvet.com.au/vet/lakeside/");
        r.set(Field.NAME, "Lakeside Veterinary Hospital");
        r.set(Field.STREET, "5 Lake Rd");
        r.set(Field.SUBURB, "Lakeside");
        r.set(Field.STATE, "NSW");
        r.set(Field.POSTCODE, "2200");
        r.set(Field.PHONE, "(02) 9000 0004");
        r.set(Field.LATITUDE, "-33.9");
        r.set(Field.LONGITUDE, "151.0");
        Normalizer.apply(r);
        return Merger.merge(List.of(r), Map.of()).getFirst();
    }

    @Test
    void abrJsonpAndDetails() {
        JsonNode d = AbrClient.unwrap("c({\"Abn\":\"74172177893\",\"AbnStatus\":\"Active\",\"AbnStatusEffectiveFrom\":\"2004-03-01\","
            + "\"EntityName\":\"LAKESIDE VETERINARY HOSPITAL PTY LTD\",\"BusinessName\":[\"Lakeside Vet\"],\"Message\":\"\"})");
        Map<Field, String> values = AbrClient.detailsToValues(d);
        assertEquals("74 172 177 893", values.get(Field.ABN));
        assertEquals("LAKESIDE VETERINARY HOSPITAL PTY LTD", values.get(Field.REGISTERED_NAME));
        assertEquals("Active", values.get(Field.ABN_STATUS));
        assertEquals("2004-03-01", values.get(Field.ABN_REGISTERED_FROM));
    }

    @Test
    void abrRefusedGuidStopsTheRun() {
        assertThrows(IllegalStateException.class, () -> AbrClient.unwrap(
            "cb({\"Message\":\"The GUID entered is not recognised as a Registered Party\",\"Names\":[]})"));
    }

    @Test
    void abrPicksTheCandidateInTheSamePostcode() {
        JsonNode names = Fixtures.json("""
            [{"Abn":"11111111111","Name":"Lakeside Veterinary Hospital","Postcode":"3000","State":"VIC","Score":100},
             {"Abn":"74172177893","Name":"LAKESIDE VETERINARY HOSPITAL PTY LTD","Postcode":"2200","State":"NSW","Score":96}]
            """);
        assertEquals("74172177893", AbrClient.pickAbn(names, "Lakeside Veterinary Hospital", "2200", "NSW"));
        assertNull(AbrClient.pickAbn(names, "Lakeside Veterinary Hospital", "4000", "QLD"));

        JsonNode tie = Fixtures.json("""
            [{"Abn":"22222222222","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100},
             {"Abn":"33333333333","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100}]
            """);
        assertEquals("AMBIGUOUS", AbrClient.pickAbn(tie, "Lakeside Vet", "2200", "NSW"));
    }

    @Test
    void googlePicksANearbyPlaceWithTheSameName() {
        Clinic c = clinic();
        JsonNode places = Fixtures.json("""
            [{"displayName":{"text":"Some Other Vet"},"location":{"latitude":-33.9001,"longitude":151.0001},
              "nationalPhoneNumber":"(02) 9111 1111"},
             {"displayName":{"text":"Lakeside Vet Hospital"},"location":{"latitude":-33.9003,"longitude":151.0002},
              "googleMapsUri":"https://maps.google.com/?cid=123","businessStatus":"OPERATIONAL","rating":4.62,
              "userRatingCount":321,"websiteUri":"https://lakesidevet.com.au/?utm_source=gmb","nationalPhoneNumber":"(02) 9000 0004"}]
            """);
        JsonNode picked = GooglePlacesClient.pickPlace(places, c);
        assertNotNull(picked);
        Map<Field, String> values = GooglePlacesClient.placeToValues(picked);
        assertEquals("https://maps.google.com/?cid=123", values.get(Field.GOOGLE_MAPS_URL));
        assertEquals("4.6", values.get(Field.GOOGLE_RATING));
        assertEquals("321", values.get(Field.GOOGLE_REVIEW_COUNT));
        assertEquals("OPERATIONAL", values.get(Field.GOOGLE_BUSINESS_STATUS));
        assertEquals("https://lakesidevet.com.au", values.get(Field.WEBSITE));
    }

    @Test
    void googleRequestBody() {
        String body = new GooglePlacesClient(null, "key").requestBody(clinic());
        assertTrue(body.contains("\"textQuery\":\"Lakeside Veterinary Hospital, 5 Lake Rd, Lakeside, NSW 2200\""), body);
        assertTrue(body.contains("\"includedType\":\"veterinary_care\""), body);
        assertTrue(body.contains("\"radius\":500.0"), body);
    }
}
