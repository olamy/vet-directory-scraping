package vetdb.source;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import vetdb.Fixtures;
import vetdb.clean.Normalizer;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceParsersTest {

    private static SourceRecord clean(SourceRecord r) {
        assertNotNull(r);
        Normalizer.apply(r);
        return r;
    }

    @Test
    void rateMyVetProfile() {
        SourceRecord r = clean(RateMyVetSource.parse("https://ratemyvet.com.au/vet/4-paws-vet-neutral-bay-neutral-bay/",
            Fixtures.read("ratemyvet/4-paws-vet-neutral-bay.html")));
        assertEquals("4-paws-vet-neutral-bay-neutral-bay", r.sourceId());
        assertEquals("4 Paws Vet Neutral Bay", r.get(Field.NAME));
        assertEquals("Animal hospital", r.get(Field.CATEGORY));
        assertEquals("154 Military Rd", r.get(Field.STREET));
        assertEquals("Neutral Bay", r.get(Field.SUBURB));
        assertEquals("NSW", r.get(Field.STATE));
        assertEquals("2089", r.get(Field.POSTCODE));
        assertEquals("(02) 9953 1264", r.get(Field.PHONE));
        assertEquals("https://www.4pawsvet.com.au", r.get(Field.WEBSITE));
        assertEquals("4.7", r.get(Field.GOOGLE_RATING));
        assertEquals("175", r.get(Field.GOOGLE_REVIEW_COUNT));
        assertEquals("Dogs, cats, birds & exotics", r.get(Field.SPECIES_TREATED));
        assertEquals("Y", r.get(Field.AFTER_HOURS_EMERGENCY));
        assertEquals("Y", r.get(Field.HOME_MOBILE_VISITS));
        assertNull(r.get(Field.BOOK_ONLINE));
        assertTrue(r.get(Field.SERVICES).contains("Dental"));
        assertEquals("-33.830628", r.get(Field.LATITUDE));
        assertEquals("AU", r.country());
    }

    @Test
    void rateMyVetMessyStreetAndMobileVet() {
        SourceRecord sash = clean(RateMyVetSource.parse(
            "https://ratemyvet.com.au/vet/sash-specialist-24-7-emergency-vets-alexandria-alexandria/",
            Fixtures.read("ratemyvet/sash-alexandria.html")));
        assertEquals("5/476 Gardeners Rd", sash.get(Field.STREET));
        assertEquals("-33.92055", sash.get(Field.LATITUDE));
        assertEquals("https://sashvets.com/locations/alexandria/", sash.get(Field.WEBSITE));

        SourceRecord mobile = clean(RateMyVetSource.parse("https://ratemyvet.com.au/vet/4-paws-mobile-vet-nathalia/",
            Fixtures.read("ratemyvet/4-paws-mobile-nathalia.html")));
        assertEquals("1", mobile.get(Field.GOOGLE_REVIEW_COUNT));
        assertEquals("5.0", mobile.get(Field.GOOGLE_RATING));
        assertEquals("Y", mobile.get(Field.HOME_MOBILE_VISITS));
        assertNull(mobile.get(Field.AFTER_HOURS_EMERGENCY));
        assertNull(mobile.get(Field.WEBSITE));
        assertEquals("0400 503 548", mobile.get(Field.PHONE));
    }

    @Test
    void vetNetProfile() {
        SourceRecord r = clean(VetNetSource.parse(
            "https://www.vet.net.au/vet-detail/nsw/neutral-bay/4-paws-veterinary-clinic/A22017540",
            Fixtures.read("vetnet/A22017540.html")));
        assertEquals("A22017540", r.sourceId());
        assertEquals("4 Paws Veterinary Clinic", r.get(Field.NAME));
        assertEquals("154 Military Rd", r.get(Field.STREET));
        assertEquals("Neutral Bay", r.get(Field.SUBURB));
        assertEquals("NSW", r.get(Field.STATE));
        assertEquals("2089", r.get(Field.POSTCODE));
        assertEquals("(02) 9953 1264", r.get(Field.PHONE));
        assertEquals("http://www.4pawsvet.com.au", r.get(Field.WEBSITE));
        assertEquals("https://www.facebook.com/4PawsVetClinic", r.get(Field.FACEBOOK));
        assertTrue(r.get(Field.SERVICES).contains("Boarding - Dogs"));
        assertTrue(r.get(Field.OPENING_HOURS).startsWith("Mon 8:00 AM - 7:00 PM"), r.get(Field.OPENING_HOURS));
        assertEquals("-33.830627", r.get(Field.LATITUDE));
        assertEquals("151.220978", r.get(Field.LONGITUDE));
        assertNotNull(r.get(Field.NUMBER_OF_STAFF));
        assertNotNull(r.get(Field.NUMBER_OF_VETS));
    }

    @Test
    void vetNetOtherSocialsAndMissingPage() {
        SourceRecord r = clean(VetNetSource.parse(
            "https://www.vet.net.au/vet-detail/qld/daisy-hill/daisy-hill-veterinary-clinic/A23019197",
            Fixtures.read("vetnet/A23019197.html")));
        assertTrue(r.get(Field.OTHER_SOCIALS).contains("youtube.com"));
        assertEquals("Daisy Hill", r.get(Field.SUBURB));

        SourceRecord aes = clean(VetNetSource.parse("https://www.vet.net.au/vet-detail/x/A24019615",
            Fixtures.read("vetnet/A24019615.html")));
        assertEquals("395 Tooronga Rd", aes.get(Field.STREET));
        assertEquals("1", aes.get(Field.NUMBER_OF_VETS));

        assertNull(VetNetSource.parse("https://www.vet.net.au/vet-detail/x/A00000001", Fixtures.read("vetnet/missing.html")));
    }

    @Test
    void piaProfiles() {
        JsonNode rest = Fixtures.json(Fixtures.read("pia/rest-page.json"));
        JsonNode frankstonItem = null;
        for (JsonNode item : rest) {
            if (item.path("id").asInt() == 14084) {
                frankstonItem = item;
            }
        }
        SourceRecord r = clean(PiaSource.parse(frankstonItem, Fixtures.read("pia/frankston-heights.html")));
        assertEquals("14084", r.sourceId());
        assertEquals("Frankston Heights Veterinary Clinic", r.get(Field.NAME));
        assertEquals("231 Frankston-Flinders Rd", r.get(Field.STREET));
        assertEquals("Frankston South", r.get(Field.SUBURB));
        assertEquals("VIC", r.get(Field.STATE));
        assertEquals("3199", r.get(Field.POSTCODE));
        assertEquals("(03) 5971 4888", r.get(Field.PHONE));
        assertEquals("http://frankstonvet.com.au", r.get(Field.WEBSITE));
        assertEquals("Vet clinic", r.get(Field.CATEGORY));
        assertEquals("-38.175097", r.get(Field.LATITUDE));
        assertEquals("2016-09-08T07:54:30", r.sourceModified());

        JsonNode bexleyItem = Fixtures.json("""
            {"id": 12838, "link": "https://www.piavetdirectory.com.au/listings/bexley-veterinary-hospital/",
             "modified": "2016-09-01T00:00:00", "meta": {"_job_location": "568 Forest Road, Bexley, Sydney, New South Wales, 2207, Australia"}}
            """);
        SourceRecord bexley = clean(PiaSource.parse(bexleyItem, Fixtures.read("pia/bexley.html")));
        assertEquals("Animal hospital", bexley.get(Field.CATEGORY));
        assertEquals("568 Forest Rd", bexley.get(Field.STREET));
        assertEquals("NSW", bexley.get(Field.STATE));
    }

    @Test
    void piaLocationStringFallback() {
        SourceRecord r = new SourceRecord("pia", "1", "x");
        PiaSource.fromLocationString(r, "231 Frankston Flinders Road, Frankston, Melbourne, Victoria, 3199, Australia");
        assertEquals("231 Frankston Flinders Road", r.get(Field.STREET));
        assertEquals("Frankston", r.get(Field.SUBURB));
        assertEquals("Victoria", r.get(Field.STATE));
        assertEquals("3199", r.get(Field.POSTCODE));
    }

    @Test
    void bestForPetProfileWithStoreSearch() {
        JsonNode store = Fixtures.json("""
            {"id": 4989, "link": "https://bestforpet.com.au/clinics/lincoln-veterinary-centre/", "modified": "2026-01-01T00:00:00"}
            """);
        JsonNode ajax = BestForPetSource.findStore(Fixtures.json(Fixtures.read("bestforpet/ajax-lincoln.json")), "4989");
        SourceRecord r = clean(BestForPetSource.parse(store, Set.of("best-for-pet", "payment-plans"),
            Fixtures.read("bestforpet/lincoln.html"), ajax));
        assertEquals("Lincoln Veterinary Centre", r.get(Field.NAME));
        assertEquals("20 Windsor Ave", r.get(Field.STREET));
        assertEquals("Port Lincoln", r.get(Field.SUBURB));
        assertEquals("SA", r.get(Field.STATE));
        assertEquals("5606", r.get(Field.POSTCODE));
        assertEquals("(08) 8682 3100", r.get(Field.PHONE));
        assertEquals("reception@lincolnvet.com.au", r.get(Field.EMAIL));
        assertEquals("https://www.lincolnvet.com.au", r.get(Field.WEBSITE));
        assertEquals("4.3", r.get(Field.DIRECTORY_RATING));
        assertEquals("Y", r.get(Field.WELLNESS_PLAN));
        assertEquals("Y", r.get(Field.PAYMENT_PLAN));
        assertEquals("Y", r.get(Field.DIGITAL_PAYMENT));
        assertEquals("GapOnly; AfterPay", r.get(Field.PAYMENT_OPTIONS));
        assertEquals("Y", r.get(Field.BOOK_ONLINE));
        assertEquals("https://ap-booking.vetstoria.com/booking/5fb3183211af3/", r.get(Field.BOOKING_URL));
        assertEquals("4", r.get(Field.NUMBER_OF_VETS));
        assertEquals("4", r.get(Field.NUMBER_OF_STAFF));
        assertTrue(r.get(Field.OPENING_HOURS).startsWith("Mon 8:30 AM - 5:30 PM"), r.get(Field.OPENING_HOURS));
        assertTrue(r.get(Field.OPENING_HOURS).endsWith("Sun Closed"), r.get(Field.OPENING_HOURS));
        assertEquals("Dogs, cats, birds, rodents, reptiles, cattle, sheep", r.get(Field.SPECIES_TREATED));
        assertNull(r.get(Field.AFTER_HOURS_EMERGENCY));
    }

    @Test
    void bestForPetWithoutStoreSearch() {
        JsonNode lincoln = Fixtures.json("""
            {"id": 4989, "link": "https://bestforpet.com.au/clinics/lincoln-veterinary-centre/"}
            """);
        SourceRecord r = clean(BestForPetSource.parse(lincoln, Set.of(), Fixtures.read("bestforpet/lincoln.html"), null));
        assertEquals("reception@lincolnvet.com.au", r.get(Field.EMAIL));
        assertEquals("(08) 8682 3100", r.get(Field.PHONE));
        assertEquals("Y", r.get(Field.BOOK_ONLINE));

        JsonNode peninsula = Fixtures.json("""
            {"id": 4773, "link": "https://bestforpet.com.au/clinics/peninsula-vet-care-emergency-and-referral-hospital/"}
            """);
        SourceRecord p = clean(BestForPetSource.parse(peninsula, Set.of(), Fixtures.read("bestforpet/peninsula.html"), null));
        assertEquals("Y", p.get(Field.AFTER_HOURS_EMERGENCY));
        assertEquals("161 Tyabb Rd", p.get(Field.STREET));
        assertNull(p.get(Field.BOOK_ONLINE));
    }

    @Test
    void sitemapUrlsLoseInvisibleCharacters() {
        String xml = "<urlset><url><loc>https://www.vet.net.au/vet-detail/nsw/​dee-why/x/A22017861</loc></url>"
            + "<url><loc> https://a.com/?x=1&amp;y=2 </loc></url></urlset>";
        assertEquals(java.util.List.of("https://www.vet.net.au/vet-detail/nsw/dee-why/x/A22017861", "https://a.com/?x=1&y=2"),
            Extract.sitemapLocs(xml));
    }

    @Test
    void helpers() {
        assertEquals("Mon 8:00 AM - 7:00 PM", Extract.hoursEntry("Monday", "08:00 am - 07:00 pm"));
        assertEquals("Sun Closed", Extract.hoursEntry("Sunday", ""));
        assertTrue(Extract.isVetRole("Veterinarian"));
        assertTrue(Extract.isVetRole("Surgery Registrar"));
        assertTrue(Extract.isVetRole("Practice Owner & Veterinarian"));
        assertTrue(!Extract.isVetRole("Veterinary Nurse"));
        assertTrue(!Extract.isVetRole("Practice Manager"));
        assertEquals("Dogs, cats, horses", Extract.speciesFromText("Founded in 1990. We treat dogs, cats and horses."));
        assertNull(Extract.speciesFromText("Our dog park is next door."));
    }
}
