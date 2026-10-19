package vetdb.source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
 * bestforpet.com.au: a WordPress "WP Store Locator" site.
 * The REST API lists the clinics, each profile page has most details, and the
 * store-search AJAX call (the one the site's own map uses) adds email, booking link
 * and emergency flags.
 */
public final class BestForPetSource implements Source {

    public static final String NAME = "bestforpet";
    private static final String BASE = "https://bestforpet.com.au";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WPSL_MAP = Pattern.compile("var wpslMap_0 = (\\{.*?\\});");
    private static final Pattern RATING = Pattern.compile("Rating:\\s*([0-9.]+)");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String host() {
        return "bestforpet.com.au";
    }

    @Override
    public long delayMillis() {
        // Wordfence sits in front of this site, so go easy.
        return 2500;
    }

    @Override
    public void run(Fetcher fetcher, int limit, Consumer<SourceRecord> sink) {
        List<JsonNode> stores = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonNode arr = readJson(fetcher.get(BASE + "/wp-json/wp/v2/wpsl_stores?per_page=100&page=" + page));
            if (arr == null || !arr.isArray() || arr.isEmpty()) {
                break;
            }
            arr.forEach(stores::add);
            if (arr.size() < 100) {
                break;
            }
        }
        Map<Integer, String> categories = new HashMap<>();
        JsonNode cats = readJson(fetcher.get(BASE + "/wp-json/wp/v2/wpsl_store_category?per_page=100"));
        if (cats != null) {
            cats.forEach(c -> categories.put(c.path("id").asInt(), c.path("slug").asText()));
        }

        int total = limit > 0 ? Math.min(limit, stores.size()) : stores.size();
        System.out.printf("[%s] %d clinics in the REST API, processing %d%n", NAME, stores.size(), total);
        for (int i = 0; i < total; i++) {
            JsonNode store = stores.get(i);
            String link = store.path("link").asText();
            String html = fetcher.get(link);
            if (html == null) {
                System.err.printf("[%s] could not get %s%n", NAME, link);
                continue;
            }
            JsonNode location = mapLocation(html);
            JsonNode ajax = null;
            if (location != null && location.hasNonNull("lat")) {
                String url = BASE + "/wp-admin/admin-ajax.php?action=store_search&lat=" + location.path("lat").asText()
                    + "&lng=" + location.path("lng").asText() + "&max_results=5&search_radius=10&autoload=1";
                ajax = findStore(readJson(fetcher.get(url)), store.path("id").asText());
            }
            Set<String> slugs = new LinkedHashSet<>();
            store.path("wpsl_store_category").forEach(id -> {
                String slug = categories.get(id.asInt());
                if (slug != null) {
                    slugs.add(slug);
                }
            });
            sink.accept(parse(store, slugs, html, ajax));
            if ((i + 1) % 50 == 0) {
                System.out.printf("[%s] %d/%d%n", NAME, i + 1, total);
            }
        }
    }

    static JsonNode findStore(JsonNode results, String id) {
        if (results == null || !results.isArray()) {
            return null;
        }
        for (JsonNode r : results) {
            if (r.path("id").asText().equals(id)) {
                return r;
            }
        }
        return null;
    }

    static JsonNode mapLocation(String html) {
        JsonNode map = readJson(Extract.group(WPSL_MAP, html, 1));
        if (map == null) {
            return null;
        }
        JsonNode first = map.path("locations").path(0);
        return first.isMissingNode() ? null : first;
    }

    /**
     * Builds a record from the REST entry, the profile page and (when found) the store-search entry.
     * The page repeats blocks for desktop and mobile, so we always take the first match.
     */
    public static SourceRecord parse(JsonNode store, Set<String> categorySlugs, String html, JsonNode ajax) {
        Document doc = Jsoup.parse(html, store.path("link").asText());
        SourceRecord r = new SourceRecord(NAME, store.path("id").asText(), store.path("link").asText());
        r.sourceModified(store.path("modified").asText(null));
        JsonNode ajaxNode = ajax == null ? JSON.createObjectNode() : ajax;

        JsonNode loc = mapLocation(html);
        if (loc != null) {
            r.set(Field.NAME, loc.path("store").asText(null));
            String address2 = loc.path("address2").asText("");
            String address = loc.path("address").asText(null);
            r.set(Field.STREET, address2.isBlank() ? address : address2 + ", " + address);
            r.set(Field.SUBURB, loc.path("city").asText(null));
            r.set(Field.STATE, loc.path("state").asText(null));
            r.set(Field.POSTCODE, loc.path("zip").asText(null));
            r.country(loc.path("country").asText(null));
            r.set(Field.LATITUDE, loc.path("lat").asText(null));
            r.set(Field.LONGITUDE, loc.path("lng").asText(null));
        }
        if (!r.has(Field.NAME)) {
            r.set(Field.NAME, store.path("title").path("rendered").asText(null));
        }

        Element contact = doc.selectFirst("ul.fusion-checklist:has(i.fa-map-marker-alt)");
        r.set(Field.PHONE, firstNonBlank(ajaxNode.path("phone").asText(null),
            contact == null ? null : Extract.text(contact.selectFirst("li:has(i.fa-phone-alt) a[href^=tel:]"))));
        r.set(Field.WEBSITE, firstNonBlank(ajaxNode.path("url").asText(null),
            contact == null ? null : Extract.attr(contact.selectFirst("li:has(i.fa-globe) a[href]"), "href")));
        String cfEmail = contact == null ? null
            : Normalizer.cloudflareEmail(Extract.attr(contact.selectFirst("span.__cf_email__"), "data-cfemail"));
        r.set(Field.EMAIL, firstNonBlank(ajaxNode.path("email").asText(null), ajaxNode.path("cemail").asText(null), cfEmail));

        r.set(Field.DIRECTORY_RATING, Extract.group(RATING, Extract.attr(doc.selectFirst("div.awb-stars-rating"), "aria-label"), 1));

        List<String> services = new ArrayList<>();
        Element serviceList = doc.selectFirst("ul.ds-list-2-col");
        if (serviceList != null) {
            serviceList.select("li .fusion-li-item-content").forEach(e -> services.add(e.text()));
        }
        r.set(Field.SERVICES, String.join("; ", services));
        String servicesLower = String.join(" ", services).toLowerCase();

        Element paymentBox = doc.selectFirst(".payment-options-container");
        List<String> payments = new ArrayList<>();
        if (paymentBox != null) {
            paymentBox.select("img.payment-logo[alt]").forEach(img -> {
                if (!payments.contains(img.attr("alt"))) {
                    payments.add(img.attr("alt"));
                }
            });
        }
        Extract.payments(r, payments);
        if (categorySlugs.contains("payment-plans")) {
            r.set(Field.PAYMENT_PLAN, "Y");
        }
        if (ajaxNode.path("best_for_pet_wellness_plan").asBoolean(false)
            || categorySlugs.contains("best-for-pet") || servicesLower.contains("wellness plan")) {
            r.set(Field.WELLNESS_PLAN, "Y");
        }

        r.set(Field.OPENING_HOURS, openingHours(doc));
        team(doc, r);

        String bookingUrl = ajaxNode.path("booking_url").asText("");
        if (bookingUrl.startsWith("http")) {
            r.set(Field.BOOK_ONLINE, "Y");
            r.set(Field.BOOKING_URL, bookingUrl);
        } else {
            String button = Extract.attr(doc.selectFirst("a.ds-clinic-booking-btn[href*=book-online]"), "href");
            if (button != null) {
                r.set(Field.BOOK_ONLINE, "Y");
                r.set(Field.BOOKING_URL, button);
            }
        }

        boolean emergency = "1".equals(ajaxNode.path("emergency_care").asText())
            || "1".equals(ajaxNode.path("after_hours_service").asText())
            || categorySlugs.contains("emergency-vet")
            || servicesLower.contains("emergency");
        if (emergency) {
            r.set(Field.AFTER_HOURS_EMERGENCY, "Y");
        }
        if (categorySlugs.contains("emergency-vet")) {
            r.set(Field.CATEGORY, "Emergency vet");
        }

        r.set(Field.SPECIES_TREATED, Extract.speciesFromText(Extract.text(doc.selectFirst(".fusion-content-tb-1"))));

        r.raw().put("categories", String.join(",", categorySlugs));
        if (ajaxNode.hasNonNull("sf_account_id")) {
            r.raw().put("sf_account_id", ajaxNode.path("sf_account_id").asText());
        }
        return r;
    }

    /** The regular "Opening hours" table; emergency and specialist tables are skipped. */
    private static String openingHours(Document doc) {
        Element heading = doc.selectFirst("h4:matchesOwn(^\\s*Opening hours\\s*$)");
        Element table = Extract.firstAfter(doc, heading, "table.wpsl-opening-hours");
        if (table == null) {
            table = doc.selectFirst("table.wpsl-opening-hours");
        }
        if (table == null) {
            return null;
        }
        List<String> days = new ArrayList<>();
        for (Element row : table.select("tr")) {
            if (row.children().size() >= 2) {
                days.add(Extract.hoursEntry(row.child(0).text(), row.child(1).text()));
            }
        }
        return days.isEmpty() ? null : String.join("; ", days);
    }

    /** Counts the "Meet the team" carousel: everyone is staff, vets are counted by role. */
    private static void team(Document doc, SourceRecord r) {
        Element heading = doc.selectFirst("h4:matchesOwn(^\\s*Meet the team\\s*$)");
        Element carousel = Extract.firstAfter(doc, heading, "div.awb-carousel");
        if (carousel == null) {
            return;
        }
        Map<String, String> people = new LinkedHashMap<>();
        for (Element slide : carousel.select(".swiper-slide")) {
            String name = Extract.text(slide.selectFirst("h3"));
            if (name != null) {
                people.putIfAbsent(name, Extract.text(slide.selectFirst("h4")));
            }
        }
        if (people.isEmpty()) {
            return;
        }
        r.set(Field.NUMBER_OF_STAFF, people.size());
        r.set(Field.NUMBER_OF_VETS, people.values().stream().filter(Extract::isVetRole).count());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
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
