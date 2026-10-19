package vetdb.source;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vetdb.clean.Normalizer;
import vetdb.http.Fetcher;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/**
 * piavetdirectory.com.au (Pet Insurance Australia): WordPress with WP Job Manager.
 * The REST API lists every published listing; the profile page adds phone,
 * coordinates and a split address. Most listings date from 2016.
 */
public final class PiaSource implements Source {

    public static final String NAME = "pia";
    private static final String BASE = "https://www.piavetdirectory.com.au";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String host() {
        return "www.piavetdirectory.com.au";
    }

    @Override
    public long delayMillis() {
        return 1000;
    }

    @Override
    public void run(Fetcher fetcher, int limit, Consumer<SourceRecord> sink) {
        List<JsonNode> listings = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode arr = readJson(fetcher.get(BASE + "/wp-json/wp/v2/job-listings?per_page=100&page=" + page));
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            arr.forEach(listings::add);
            if (arr.size() < 100) {
                break;
            }
        }
        int total = limit > 0 ? Math.min(limit, listings.size()) : listings.size();
        System.out.printf("[%s] %d listings in the REST API, processing %d%n", NAME, listings.size(), total);
        for (int i = 0; i < total; i++) {
            JsonNode item = listings.get(i);
            String link = item.path("link").asText();
            String html = fetcher.get(link);
            if (html == null) {
                System.err.printf("[%s] could not get %s%n", NAME, link);
                continue;
            }
            SourceRecord r = parse(item, html);
            if (r != null) {
                sink.accept(r);
            }
            if ((i + 1) % 100 == 0) {
                System.out.printf("[%s] %d/%d%n", NAME, i + 1, total);
            }
        }
    }

    /** Returns null for expired listings. */
    public static SourceRecord parse(JsonNode item, String html) {
        String link = item.path("link").asText();
        Document doc = Jsoup.parse(html, link);
        Element article = doc.selectFirst("article.job_listing");
        if (article != null && article.hasClass("status-expired")) {
            return null;
        }
        SourceRecord r = new SourceRecord(NAME, item.path("id").asText(), link);
        r.sourceModified(item.path("modified").asText(null));
        JsonNode meta = item.path("meta");
        String location = meta.path("_job_location").asText(null);
        r.raw().put("job_location", location);

        r.set(Field.NAME, Extract.text(doc.selectFirst("h1.entry-title")));
        if (!r.has(Field.NAME)) {
            r.set(Field.NAME, item.path("title").path("rendered").asText(null));
        }
        Element phone = doc.selectFirst("a.listing--phone");
        r.set(Field.PHONE, phone == null ? null : phone.attr("href").replaceFirst("^tel:", ""));
        r.set(Field.WEBSITE, Extract.attr(doc.selectFirst("a.listing--website"), "href"));
        if (!r.has(Field.WEBSITE)) {
            r.set(Field.WEBSITE, meta.path("_company_website").asText(null));
        }

        Element address = doc.selectFirst("address.listing-address");
        if (address != null) {
            String number = Extract.text(address.selectFirst(".address__street-no"));
            String street = Extract.text(address.selectFirst(".address__street"));
            if (number != null && street != null && !street.startsWith(number)) {
                street = number + " " + street;
            }
            r.set(Field.STREET, street);
            r.set(Field.SUBURB, Extract.text(address.selectFirst(".address__city")));
            r.set(Field.POSTCODE, Extract.text(address.selectFirst(".address__postcode")));
            r.set(Field.STATE, Extract.text(address.selectFirst(".address__state-short")));
            r.country(Extract.text(address.selectFirst(".address__country-short")));
        }
        if (addressLooksBroken(r) && location != null) {
            fromLocationString(r, location);
            r.flag("ADDRESS_FROM_LOCATION");
        } else if (!r.has(Field.STREET) && location != null) {
            // Many profiles leave the street empty while the REST location string still has it.
            String first = location.split("\\s*,\\s*")[0];
            if (first.matches(".*\\d.*")) {
                r.set(Field.STREET, first);
                r.flag("STREET_FROM_LOCATION");
            }
        }

        Element listing = doc.selectFirst("div.single_job_listing");
        r.set(Field.LATITUDE, Extract.attr(listing, "data-latitude"));
        r.set(Field.LONGITUDE, Extract.attr(listing, "data-longitude"));
        if (!r.has(Field.LATITUDE)) {
            r.set(Field.LATITUDE, Extract.attr(doc.selectFirst("meta[itemprop=latitude]"), "content"));
            r.set(Field.LONGITUDE, Extract.attr(doc.selectFirst("meta[itemprop=longitude]"), "content"));
        }

        if (article != null) {
            if (article.hasClass("job_listing_category-mobile-vets")) {
                r.set(Field.HOME_MOBILE_VISITS, "Y");
            }
            if (article.hasClass("job_listing_category-animal-hospital")) {
                r.set(Field.CATEGORY, "Animal hospital");
            } else if (article.hasClass("job_listing_category-mobile-vets")) {
                r.set(Field.CATEGORY, "Mobile vet");
            } else if (article.hasClass("job_listing_category-vets")) {
                r.set(Field.CATEGORY, "Vet clinic");
            }
        }

        // Most descriptions are just the "send us your images" promo; real ones can hold email and socials.
        Element description = doc.selectFirst("div.job_description");
        if (description != null && description.selectFirst(".listing_promo") == null) {
            r.set(Field.EMAIL, Extract.attr(description.selectFirst("a[href^=mailto:]"), "href"));
            List<String> others = new ArrayList<>();
            for (Element a : description.select("a[href]")) {
                String url = a.attr("href");
                String kind = Normalizer.socialKind(url);
                if ("facebook".equals(kind) && !r.has(Field.FACEBOOK)) {
                    r.set(Field.FACEBOOK, url);
                } else if ("instagram".equals(kind) && !r.has(Field.INSTAGRAM)) {
                    r.set(Field.INSTAGRAM, url);
                } else if ("other".equals(kind)) {
                    others.add(url);
                }
            }
            r.set(Field.OTHER_SOCIALS, String.join("; ", others));
            r.set(Field.SPECIES_TREATED, Extract.speciesFromText(description.text()));
        }
        return r;
    }

    /** The split address on PIA is sometimes shuffled (a street name in the suburb field, the state as country). */
    private static boolean addressLooksBroken(SourceRecord r) {
        String suburb = r.get(Field.SUBURB);
        return Normalizer.state(r.get(Field.STATE)) == null
            || Normalizer.postcode(r.get(Field.POSTCODE)) == null
            || suburb == null
            || Normalizer.abbreviateStreetTypes("x " + suburb).matches(".* (Rd|St|Ave|Hwy|Pde|Cres|Dr|Ct|Pl|Tce|Bvd)$");
    }

    /** Parses "231 Frankston Flinders Road, Frankston, Melbourne, Victoria, 3199, Australia". */
    static void fromLocationString(SourceRecord r, String location) {
        String[] parts = location.split("\\s*,\\s*");
        int stateIndex = -1;
        for (int i = 0; i < parts.length; i++) {
            if (Normalizer.postcode(parts[i]) != null && parts[i].matches("\\d{3,4}")) {
                r.set(Field.POSTCODE, parts[i]);
            } else if (stateIndex < 0 && i > 0 && Normalizer.state(parts[i]) != null) {
                stateIndex = i;
                r.set(Field.STATE, parts[i]);
            }
        }
        if (parts.length > 0) {
            r.set(Field.STREET, parts[0]);
        }
        if (parts.length > 1 && (stateIndex < 0 || stateIndex > 1)) {
            r.set(Field.SUBURB, parts[1]);
        }
    }

    private static JsonNode readJson(String s) {
        if (s == null) {
            return null;
        }
        try {
            return JSON.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
