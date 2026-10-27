package vetdb.merge;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import vetdb.Fixtures;
import vetdb.clean.Normalizer;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.Field;
import vetdb.model.SourceRecord;
import vetdb.source.RateMyVetSource;
import vetdb.source.VetNetSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergerTest {

    private static SourceRecord record(String source, String id, String name, String street, String postcode, String phone) {
        SourceRecord r = new SourceRecord(source, id, "https://" + source + "/" + id);
        r.set(Field.NAME, name);
        r.set(Field.STREET, street);
        r.set(Field.SUBURB, "Somewhere");
        r.set(Field.STATE, "NSW");
        r.set(Field.POSTCODE, postcode);
        r.set(Field.PHONE, phone);
        Normalizer.apply(r);
        return r;
    }

    @Test
    void sameClinicOnTwoSitesBecomesOne() {
        SourceRecord rmv = RateMyVetSource.parse("https://ratemyvet.com.au/vet/4-paws-vet-neutral-bay-neutral-bay/",
            Fixtures.read("ratemyvet/4-paws-vet-neutral-bay.html"));
        SourceRecord vetnet = VetNetSource.parse(
            "https://www.vet.net.au/vet-detail/nsw/neutral-bay/4-paws-veterinary-clinic/A22017540",
            Fixtures.read("vetnet/A22017540.html"));
        Normalizer.apply(rmv);
        Normalizer.apply(vetnet);

        List<Clinic> clinics = Merger.merge(List.of(vetnet, rmv), Map.of());
        assertEquals(1, clinics.size());
        Clinic c = clinics.getFirst();
        assertEquals(List.of("ratemyvet", "vetnet"), c.sources());
        assertEquals(Clinic.Verification.VERIFIED, c.verification());
        assertEquals("4 Paws Vet Neutral Bay", c.get(Field.NAME));
        assertEquals("154 Military Rd", c.get(Field.STREET));
        // Facebook only comes from vet.net.au, rating only from RateMyVet.
        assertEquals("https://www.facebook.com/4PawsVetClinic", c.get(Field.FACEBOOK));
        assertEquals("4.7", c.get(Field.GOOGLE_RATING));
        assertEquals("vetnet", c.provenance().get(Field.FACEBOOK));
        assertEquals("ratemyvet:4-paws-vet-neutral-bay-neutral-bay", c.anchor().key());
    }

    @Test
    void sameAddressDifferentClinicIsFlaggedNotMerged() {
        SourceRecord a = record("ratemyvet", "a", "Harbour Vet Hospital", "10 Main Street", "2000", "(02) 9000 0001");
        SourceRecord b = record("pia", "b", "Sunrise Cat Clinic", "10 Main St", "2000", "(02) 9000 0002");
        List<Clinic> clinics = Merger.merge(List.of(a, b), Map.of());
        assertEquals(2, clinics.size());
        assertTrue(clinics.get(0).flags().stream().anyMatch(f -> f.startsWith("SAME_ADDRESS_AS_")));
        assertTrue(clinics.get(1).flags().stream().anyMatch(f -> f.startsWith("SAME_ADDRESS_AS_")));
    }

    @Test
    void samePhoneAndPostcodeMerges() {
        SourceRecord a = record("ratemyvet", "a", "Northside Vets", "1 Smith St", "2060", "(02) 9000 0003");
        SourceRecord b = record("pia", "b", "Northside Veterinary Surgery", "Shop 2, 1-3 Smith Street", "2060", "02 9000 0003");
        List<Clinic> clinics = Merger.merge(List.of(a, b), Map.of());
        assertEquals(1, clinics.size());
    }

    @Test
    void samePhoneAndNameMergeWhenOneListingHasNoLocation() {
        SourceRecord complete = record("ratemyvet", "a", "Vets at Amaroo", "Shop 1 2 Emerald Way", "2914", "(02) 6230 2262");
        SourceRecord partial = record("ratemyvet", "b", "Vets at Amaroo", null, null, "02 6230 2262");
        List<Clinic> clinics = Merger.merge(List.of(complete, partial), Map.of());
        assertEquals(1, clinics.size());
        assertEquals("same_phone_name", clinics.getFirst().matchRules().get(partial.key()));
    }

    @Test
    void samePhoneAndNameDoesNotMergeConflictingPostcodes() {
        SourceRecord a = record("ratemyvet", "a", "Chain Vet", "1 Alpha St", "2000", "(02) 9000 0003");
        SourceRecord b = record("ratemyvet", "b", "Chain Vet", "9 Beta Rd", "2150", "02 9000 0003");
        assertEquals(2, Merger.merge(List.of(a, b), Map.of()).size());
    }

    @Test
    void chainNumbersDoNotMerge() {
        SourceRecord a = record("ratemyvet", "a", "Greencross Vets Alpha", "1 Alpha St", "2100", "1300 000 111");
        SourceRecord b = record("ratemyvet", "b", "Greencross Vets Beta", "9 Beta Rd", "2100", "1300 000 111");
        assertEquals(2, Merger.merge(List.of(a, b), Map.of()).size());
    }

    @Test
    void addressKeysIgnoreUnitsAndSpelling() {
        assertEquals(MatchKeys.addressKey("5/476 Gardeners Rd", "2015"), MatchKeys.addressKey("Shop 5, 476 Gardeners Road", "2015"));
        assertEquals(MatchKeys.addressKey("12 Smith St", "2000"), MatchKeys.addressKey("12-14 Smith Street", "2000"));
        assertNotEquals(MatchKeys.addressKey("12 Smith St", "2000"), MatchKeys.addressKey("12 Smith St", "2001"));
        assertEquals("lot 5 main rd|2000", MatchKeys.addressKey("Lot 5 Main Road", "2000"));
    }

    @Test
    void nameSimilarity() {
        assertTrue(MatchKeys.nameSimilarity("4 Paws Vet Neutral Bay", "4 Paws Veterinary Clinic") >= 0.85);
        assertTrue(MatchKeys.nameSimilarity("Harbour Vet Hospital", "Sunrise Cat Clinic") < 0.8);
        assertEquals("4 paws", MatchKeys.nameKey("4 Paws Veterinary Clinic Pty Ltd"));
    }

    @Test
    void yesWinsAndWebsiteComesFromBestForPet() {
        SourceRecord a = record("ratemyvet", "a", "Lakeside Vet", "5 Lake Rd", "2200", "(02) 9000 0004");
        a.set(Field.WEBSITE, "https://rmv-site.com.au");
        SourceRecord b = record("bestforpet", "b", "Lakeside Vet", "5 Lake Road", "2200", "(02) 9000 0004");
        b.set(Field.WEBSITE, "https://bfp-site.com.au");
        b.set(Field.AFTER_HOURS_EMERGENCY, "Y");
        b.set(Field.SERVICES, "Dental; Surgery");
        a.set(Field.SERVICES, "dental; Emergency");
        Clinic c = Merger.merge(List.of(a, b), Map.of()).getFirst();
        assertEquals("https://bfp-site.com.au", c.get(Field.WEBSITE));
        assertEquals("Y", c.get(Field.AFTER_HOURS_EMERGENCY));
        assertEquals("dental; Emergency; Surgery", c.get(Field.SERVICES));
        assertNull(c.get(Field.HOME_MOBILE_VISITS));
    }

    @Test
    void rejectsNonVetsAndClosedClinics() {
        SourceRecord groomer = record("pia", "g", "Happy Paws Dog Grooming", "1 A St", "2300", null);
        SourceRecord vetGroomer = record("pia", "v", "Grooming and Vet Clinic", "2 B St", "2300", null);
        SourceRecord closed = record("ratemyvet", "c", "Old Town Vet", "3 C St", "2300", null);
        Map<String, Map<String, Enrichment>> enrichment = Map.of("google", Map.of("ratemyvet:c",
            new Enrichment("google", "ratemyvet:c", "MATCHED", Map.of(Field.GOOGLE_BUSINESS_STATUS, "CLOSED_PERMANENTLY"))));
        List<Clinic> clinics = Merger.merge(List.of(groomer, vetGroomer, closed), enrichment);
        Map<String, Clinic> byName = new java.util.HashMap<>();
        clinics.forEach(c -> byName.put(c.get(Field.NAME), c));
        assertEquals("NOT_A_VET", byName.get("Happy Paws Dog Grooming").rejectReason());
        assertNull(byName.get("Grooming and Vet Clinic").rejectReason());
        assertEquals("CLOSED_PERMANENTLY", byName.get("Old Town Vet").rejectReason());
    }

    @Test
    void abrMatchVerifiesASingleSourceClinic() {
        SourceRecord a = record("ratemyvet", "x", "Bush Vet", "7 Gum Rd", "2400", "(02) 6000 0005");
        Clinic plain = Merger.merge(List.of(a), Map.of()).getFirst();
        assertEquals(Clinic.Verification.SINGLE_SOURCE, plain.verification());

        Map<String, Map<String, Enrichment>> enrichment = Map.of("abr", Map.of("ratemyvet:x", new Enrichment("abr", "ratemyvet:x", "MATCHED",
            Map.of(Field.ABN, "12 345 678 901", Field.ABN_STATUS, "Active", Field.REGISTERED_NAME, "BUSH VET PTY LTD"))));
        Clinic enriched = Merger.merge(List.of(a), enrichment).getFirst();
        assertEquals(Clinic.Verification.VERIFIED, enriched.verification());
        assertEquals("12 345 678 901", enriched.get(Field.ABN));
        assertEquals("abr", enriched.provenance().get(Field.ABN));
    }
}
