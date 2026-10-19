package vetdb.clean;

import org.junit.jupiter.api.Test;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizerTest {

    @Test
    void phonesUseTheNationalFormat() {
        assertEquals("(02) 9953 1264", Normalizer.phone("(02) 9953-1264").national());
        assertEquals("+61299531264", Normalizer.phone("(02) 9953-1264").e164());
        assertEquals("(02) 9953 1264", Normalizer.phone("+61299531264").national());
        assertEquals("(02) 9953 1264", Normalizer.phone("tel:(02) 9953 1264").national());
        assertEquals("0400 503 548", Normalizer.phone("0400503548").national());
        assertEquals("1300 123 456", Normalizer.phone("1300123456").national());
        assertEquals("(03) 5979 9600", Normalizer.phone("(03) 5979 9600 / 0412 345 678").national());
        assertNull(Normalizer.phone("12345"));
        assertNull(Normalizer.phone("N/A"));
    }

    @Test
    void missingAreaCodeComesFromTheState() {
        SourceRecord r = new SourceRecord("ratemyvet", "x", "x");
        r.set(Field.STATE, "NSW");
        r.set(Field.PHONE, "94562492");
        Normalizer.apply(r);
        assertEquals("(02) 9456 2492", r.get(Field.PHONE));
        assertTrue(r.flags().contains("PHONE_AREA_CODE_ADDED"));
    }

    @Test
    void chainNumbersAreShared() {
        assertTrue(Normalizer.isSharedNumber(Normalizer.phone("1300 123 456").e164()));
        assertTrue(Normalizer.isSharedNumber(Normalizer.phone("1800 953 477").e164()));
        assertFalse(Normalizer.isSharedNumber(Normalizer.phone("(02) 9953 1264").e164()));
    }

    @Test
    void statesBecomeAbbreviations() {
        assertEquals("VIC", Normalizer.state("Victoria"));
        assertEquals("VIC", Normalizer.state("Vic."));
        assertEquals("NSW", Normalizer.state("N.S.W."));
        assertEquals("QLD", Normalizer.state("qld"));
        assertEquals("ACT", Normalizer.state("Australian Capital Territory"));
        assertNull(Normalizer.state("Auckland"));
    }

    @Test
    void postcodesHaveFourDigits() {
        assertEquals("0800", Normalizer.postcode("800"));
        assertEquals("2089", Normalizer.postcode(" 2089 "));
        assertNull(Normalizer.postcode("20899"));
        assertTrue(Normalizer.postcodeInState("2089", "NSW"));
        assertTrue(Normalizer.postcodeInState("0800", "NT"));
        assertTrue(Normalizer.postcodeInState("2600", "ACT"));
        assertFalse(Normalizer.postcodeInState("3000", "NSW"));
    }

    @Test
    void streetsAreCleaned() {
        assertEquals("5/476 Gardeners Rd", Normalizer.street("Parking entrance via Ellis Ave, 5/476 Gardeners Rd"));
        assertEquals("154 Military Rd", Normalizer.street("154 Military Road"));
        assertEquals("231 Frankston-Flinders Rd", Normalizer.street("231 Frankston-Flinders Road"));
        assertEquals("Cnr Bungower Rd and Nepean Hwy", Normalizer.street("Cnr Bungower Road and Nepean Highway"));
        assertEquals("Shop 3, 12 Main St", Normalizer.street("SHOP 3, 12 MAIN STREET"));
        assertEquals("Level 1, 100 George St", Normalizer.street("Level 1, 100 George Street"));
        // "Street" in the middle of a name is left alone.
        assertEquals("12 Street Ln", Normalizer.street("12 Street Lane"));
    }

    @Test
    void brokenCharactersAreFixed() {
        assertEquals("Vet's Clinic", Normalizer.text("Vetâ€™s Clinic"));
        assertEquals("Café Vet", Normalizer.text("CafÃ© Vet"));
        assertEquals("A&B Vets", Normalizer.text("A&amp;B Vets"));
        assertEquals("x y", Normalizer.text("  x ​  y "));
        assertEquals("North - South", Normalizer.text("North – South"));
        assertNull(Normalizer.text("   "));
    }

    @Test
    void namesAreRecasedOnlyWhenShouting() {
        assertEquals("Abc Veterinary Clinic", Normalizer.name("ABC VETERINARY CLINIC"));
        assertEquals("4 Paws Vet", Normalizer.name("4 Paws Vet"));
        assertEquals("McKinnon Vet", Normalizer.name("McKinnon Vet"));
    }

    @Test
    void urlsLoseTrackingParameters() {
        assertEquals("https://www.4pawsvet.com.au",
            Normalizer.url("https://www.4pawsvet.com.au/?utm_source=google&utm_medium=organic&utm_campaign=gmb"));
        assertEquals("http://www.example.com.au/contact?id=3",
            Normalizer.url("www.Example.com.au/contact?id=3&utm_source=x"));
        assertEquals("https://sashvets.com/locations/alexandria/",
            Normalizer.url("https://sashvets.com/locations/alexandria/?utm_source=google#map"));
        assertNull(Normalizer.url("not a url"));
    }

    @Test
    void emails() {
        assertEquals("reception@lincolnvet.com.au",
            Normalizer.cloudflareEmail("daa8bfb9bfaaaeb3b5b49ab6b3b4b9b5b6b4acbfaef4b9b5b7f4bbaf"));
        assertEquals("info@vet.com.au", Normalizer.email("mailto:Info@Vet.com.au?subject=hi"));
        assertNull(Normalizer.email("not an email"));
    }

    @Test
    void species() {
        assertEquals("Dogs, cats, birds & exotics", Normalizer.species("Dogs, cats + birds & exotics"));
        assertEquals("Dogs, cats, horses", Normalizer.species("Dogs, cats + horses"));
    }

    @Test
    void applyCleansAWholeRecordAndMovesSocialLinks() {
        SourceRecord r = new SourceRecord("pia", "1", "https://example");
        r.set(Field.NAME, "SOUTHSIDE VET");
        r.set(Field.STATE, "Victoria");
        r.set(Field.POSTCODE, "2000");
        r.set(Field.PHONE, "not a phone");
        r.set(Field.WEBSITE, "https://www.facebook.com/southsidevet");
        r.set(Field.LATITUDE, "51.5");
        r.set(Field.LONGITUDE, "-0.12");
        Normalizer.apply(r);
        assertEquals("Southside Vet", r.get(Field.NAME));
        assertEquals("VIC", r.get(Field.STATE));
        assertEquals("https://www.facebook.com/southsidevet", r.get(Field.FACEBOOK));
        assertNull(r.get(Field.WEBSITE));
        assertNull(r.get(Field.PHONE));
        assertNull(r.get(Field.LATITUDE));
        assertTrue(r.flags().contains("BAD_PHONE"));
        assertTrue(r.flags().contains("POSTCODE_STATE_MISMATCH"));
        assertTrue(r.flags().contains("BAD_COORDINATES"));
        assertEquals("SOUTHSIDE VET", r.raw().get("name"));
    }
}
