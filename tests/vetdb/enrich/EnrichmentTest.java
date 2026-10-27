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
        assertEquals(new AbrClient.Pick("74172177893", List.of()), AbrClient.pickAbn(names, "Lakeside Veterinary Hospital", "2200", "NSW"));
        assertEquals(new AbrClient.Pick(null, List.of()), AbrClient.pickAbn(names, "Lakeside Veterinary Hospital", "4000", "QLD"));
    }

    @Test
    void abrListsTheCandidatesWhenTheSameNameIsOnTwoActiveAbns() {
        JsonNode tie = Fixtures.json("""
            [{"Abn":"22222222222","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100,"AbnStatus":"0000000001","NameType":"Business Name"},
             {"Abn":"33333333333","Name":"LAKESIDE VET","Postcode":"2200","State":"NSW","Score":100,"AbnStatus":"0000000001","NameType":"Business Name"}]
            """);
        assertEquals(new AbrClient.Pick(null, List.of("Lakeside Vet (22 222 222 222)", "LAKESIDE VET (33 333 333 333)")),
            AbrClient.pickAbn(tie, "Lakeside Vet", "2200", "NSW"));
    }

    @Test
    void abrPrefersTheActiveAbnButKeepsACancelledOneWhenItIsAllThereIs() {
        JsonNode sold = Fixtures.json("""
            [{"Abn":"22222222222","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100,"AbnStatus":"0000000002","NameType":"Business Name"},
             {"Abn":"33333333333","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100,"AbnStatus":"0000000001","NameType":"Business Name"}]
            """);
        assertEquals("33333333333", AbrClient.pickAbn(sold, "Lakeside Vet", "2200", "NSW").abn());

        JsonNode closed = Fixtures.json("""
            [{"Abn":"22222222222","Name":"Lakeside Vet","Postcode":"2200","State":"NSW","Score":100,"AbnStatus":"0000000002","NameType":"Business Name"}]
            """);
        assertEquals("22222222222", AbrClient.pickAbn(closed, "Lakeside Vet", "2200", "NSW").abn());
    }

    @Test
    void abrBreaksTiesOnTheFullNameThenTheNameType() {
        // Both reach a perfect score ("neutral bay" is part of the clinic name), the full name settles it.
        JsonNode names = Fixtures.json("""
            [{"Abn":"98031062933","Name":"NEUTRAL BAY VETERINARY SURGERY","Postcode":"2089","State":"NSW","Score":90,"NameType":"Trading Name"},
             {"Abn":"75619455346","Name":"4 PAWS NEUTRAL BAY VETERINARY CLINIC","Postcode":"2089","State":"NSW","Score":90,"NameType":"Business Name"}]
            """);
        assertEquals("75619455346", AbrClient.pickAbn(names, "4 Paws Vet Neutral Bay", "2089", "NSW").abn());

        JsonNode sameName = Fixtures.json("""
            [{"Abn":"21885376414","Name":"ACACIA VETERINARY SURGERY","Postcode":"4703","State":"QLD","Score":100,"NameType":"Trading Name"},
             {"Abn":"12792813942","Name":"Acacia Veterinary Surgery","Postcode":"4703","State":"QLD","Score":100,"NameType":"Business Name"}]
            """);
        assertEquals("12792813942", AbrClient.pickAbn(sameName, "Acacia Veterinary Surgery", "4703", "QLD").abn());
    }

    @Test
    void abrSkipsOtherTradesButReadsTrustNames() {
        JsonNode names = Fixtures.json("""
            [{"Abn":"22585632581","Name":"ALBION PARK RAIL FLORIST","Postcode":"2527","State":"NSW","Score":94},
             {"Abn":"55555555555","Name":"DALBY & DISTRICT FUNERAL SERVICES","Postcode":"4405","State":"QLD","Score":94}]
            """);
        assertEquals(new AbrClient.Pick(null, List.of()), AbrClient.pickAbn(names, "Albion Park Rail Veterinary Surgery", "2527", "NSW"));
        assertEquals(new AbrClient.Pick(null, List.of()), AbrClient.pickAbn(names, "Dalby and District Veterinary Services", "4405", "QLD"));

        JsonNode trust = Fixtures.json("""
            [{"Abn":"66666666666","Name":"The Trustee for Googong Veterinary Centre Unit Trust","Postcode":"2620","State":"NSW","Score":80}]
            """);
        assertEquals("66666666666", AbrClient.pickAbn(trust, "Googong Veterinary Centre", "2620", "NSW").abn());
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
