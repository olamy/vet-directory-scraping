package vetdb.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/** Small parsing helpers shared by the source scrapers. */
final class Extract {

    private static final Pattern LOC = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>", Pattern.DOTALL);
    private static final Pattern TIME = Pattern.compile("(?i)0?(\\d{1,2}):(\\d{2})\\s*([ap])\\.?m\\.?");

    /** Species words we look for in free text, in the order we print them. */
    private static final Map<String, String> SPECIES = new LinkedHashMap<>();

    static {
        SPECIES.put("dog", "dogs");
        SPECIES.put("cat", "cats");
        SPECIES.put("bird", "birds");
        SPECIES.put("rabbit", "rabbits");
        SPECIES.put("guinea pig", "guinea pigs");
        SPECIES.put("rodent", "rodents");
        SPECIES.put("ferret", "ferrets");
        SPECIES.put("reptile", "reptiles");
        SPECIES.put("exotic", "exotics");
        SPECIES.put("horse", "horses");
        SPECIES.put("equine", "horses");
        SPECIES.put("cattle", "cattle");
        SPECIES.put("sheep", "sheep");
        SPECIES.put("goat", "goats");
        SPECIES.put("pig", "pigs");
        SPECIES.put("alpaca", "alpacas");
        SPECIES.put("poultry", "poultry");
        SPECIES.put("chicken", "poultry");
        SPECIES.put("livestock", "livestock");
        SPECIES.put("wildlife", "wildlife");
        SPECIES.put("fish", "fish");
    }

    private Extract() {
    }

    static String text(Element e) {
        if (e == null) {
            return null;
        }
        String t = e.text();
        return t.isBlank() ? null : t.strip();
    }

    static String attr(Element e, String name) {
        if (e == null || !e.hasAttr(name)) {
            return null;
        }
        String v = e.attr(name);
        return v.isBlank() ? null : v.strip();
    }

    static String group(Pattern p, String s, int group) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.find() ? m.group(group) : null;
    }

    static List<String> sitemapLocs(String xml) {
        List<String> locs = new ArrayList<>();
        if (xml == null) {
            return locs;
        }
        Matcher m = LOC.matcher(xml);
        while (m.find()) {
            // Some sitemap URLs carry invisible characters (vet.net.au has a zero-width space in one).
            locs.add(Parser.unescapeEntities(m.group(1), false).replaceAll("[\\u200B-\\u200D\\uFEFF\\s]", ""));
        }
        return locs;
    }

    /** First element matching the query that comes after the anchor in document order. */
    static Element firstAfter(Document doc, Element anchor, String cssQuery) {
        if (anchor == null) {
            return null;
        }
        boolean seen = false;
        for (Element e : doc.getAllElements()) {
            if (e == anchor) {
                seen = true;
            } else if (seen && e.is(cssQuery)) {
                return e;
            }
        }
        return null;
    }

    /** "Available" or "Yes" means Y, "No" or "Not available" means N, anything else is unknown. */
    static String yesNo(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.startsWith("not available") || v.equals("no") || v.equals("n")) {
            return "N";
        }
        if (v.startsWith("available") || v.equals("yes") || v.equals("y") || v.startsWith("http")) {
            return "Y";
        }
        return null;
    }

    /** "Monday" + "08:00 am - 07:00 pm" becomes "Mon 8:00 AM - 7:00 PM". */
    static String hoursEntry(String day, String hours) {
        if (day == null) {
            return null;
        }
        String d = day.strip();
        d = d.length() > 3 ? d.substring(0, 3) : d;
        String h = hours == null || hours.isBlank() ? "Closed" : hours.strip();
        Matcher m = TIME.matcher(h);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1) + ":" + m.group(2) + " " + m.group(3).toUpperCase(Locale.ROOT) + "M");
        }
        m.appendTail(sb);
        return d + " " + sb.toString().replaceAll("\\s*-\\s*", " - ");
    }

    /**
     * Pulls species out of free text such as "we typically see dogs, cats, rodents and birds".
     * Only sentences that talk about seeing or treating animals are used.
     */
    static String speciesFromText(String text) {
        if (text == null) {
            return null;
        }
        List<String> found = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            String s = sentence.toLowerCase(Locale.ROOT);
            if (!s.matches(".*\\b(see|sees|seeing|treat|treats|treating|cater|caters|catering|care for|caring for|look after|looking after|welcome|patients include)\\b.*")) {
                continue;
            }
            for (Map.Entry<String, String> e : SPECIES.entrySet()) {
                if (s.matches(".*\\b" + e.getKey() + "s?\\b.*") && !found.contains(e.getValue())) {
                    found.add(e.getValue());
                }
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        String joined = String.join(", ", found);
        return Character.toUpperCase(joined.charAt(0)) + joined.substring(1);
    }

    /** Vets, surgeons and specialists count as vets; nurses and front desk staff don't. */
    static boolean isVetRole(String role) {
        if (role == null) {
            return false;
        }
        String r = role.toLowerCase(Locale.ROOT);
        if (r.matches(".*\\b(nurse|nursing|technician|assistant|receptionist|reception|student|groomer|admin\\w*)\\b.*")) {
            return false;
        }
        return r.matches(".*\\b(vet|vets|veterinarian|veterinary|surgeon|registrar|specialist|intern|resident|dr)\\b.*");
    }

    /** Sets PAYMENT_OPTIONS and derives DIGITAL_PAYMENT and PAYMENT_PLAN from the names listed. */
    static void payments(SourceRecord r, List<String> options) {
        if (options.isEmpty()) {
            return;
        }
        r.set(Field.PAYMENT_OPTIONS, String.join("; ", options));
        String all = String.join(" ", options).toLowerCase(Locale.ROOT);
        if (all.matches(".*\\b(afterpay|zip|zippay|vetpay|openpay|humm|klarna|payment plans?)\\b.*")) {
            r.set(Field.PAYMENT_PLAN, "Y");
        }
        if (all.matches(".*\\b(afterpay|zip|zippay|humm|klarna|openpay|gaponly|apple pay|google pay|paypal|paywave|paypass|contactless|eftpos|credit card|visa|mastercard|amex)\\b.*")) {
            r.set(Field.DIGITAL_PAYMENT, "Y");
        }
    }

    static String slug(String url, String prefix) {
        int i = url.indexOf(prefix);
        if (i < 0) {
            return null;
        }
        String s = url.substring(i + prefix.length()).replaceAll("^/+|/+$", "");
        return s.isEmpty() ? null : s;
    }
}
