package vetdb.clean;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import org.jsoup.parser.Parser;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/**
 * Cleaning rules shared by every source, so all records look the same
 * whatever site they came from.
 */
public final class Normalizer {

    public static final Set<String> STATES = Set.of("NSW", "VIC", "QLD", "SA", "WA", "TAS", "NT", "ACT");

    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final Pattern MOJIBAKE = Pattern.compile("[ÃÂ][\\u0080-\\u00BF]|â€|Ã.");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern URL = Pattern.compile("^(https?)://([^/?#\\s]+)([^?#\\s]*)(\\?[^#\\s]*)?(#\\S*)?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("^[a-z0-9._%+'-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+$");
    private static final Pattern STARTS_WITH_NUMBER = Pattern.compile(
        "^(?:(?:shop|unit|suite|ste|level|lvl|lot|tenancy|kiosk|building|bldg|u|t|g)\\s*)?[0-9]", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> STATE_NAMES = new LinkedHashMap<>();
    private static final Map<String, String> STREET_TYPES = new LinkedHashMap<>();

    static {
        STATE_NAMES.put("NEW SOUTH WALES", "NSW");
        STATE_NAMES.put("NSW", "NSW");
        STATE_NAMES.put("VICTORIA", "VIC");
        STATE_NAMES.put("VIC", "VIC");
        STATE_NAMES.put("QUEENSLAND", "QLD");
        STATE_NAMES.put("QLD", "QLD");
        STATE_NAMES.put("SOUTH AUSTRALIA", "SA");
        STATE_NAMES.put("SA", "SA");
        STATE_NAMES.put("WESTERN AUSTRALIA", "WA");
        STATE_NAMES.put("WA", "WA");
        STATE_NAMES.put("TASMANIA", "TAS");
        STATE_NAMES.put("TAS", "TAS");
        STATE_NAMES.put("NORTHERN TERRITORY", "NT");
        STATE_NAMES.put("NT", "NT");
        STATE_NAMES.put("AUSTRALIAN CAPITAL TERRITORY", "ACT");
        STATE_NAMES.put("ACT", "ACT");

        // Australia Post style abbreviations; the key is the lowercase spelling we may meet.
        String[][] types = {
            {"road", "Rd"}, {"rd", "Rd"},
            {"street", "St"}, {"st", "St"},
            {"avenue", "Ave"}, {"ave", "Ave"}, {"av", "Ave"},
            {"highway", "Hwy"}, {"hwy", "Hwy"},
            {"parade", "Pde"}, {"pde", "Pde"},
            {"crescent", "Cres"}, {"cres", "Cres"}, {"cr", "Cres"},
            {"drive", "Dr"}, {"dr", "Dr"}, {"drv", "Dr"},
            {"court", "Ct"}, {"ct", "Ct"},
            {"place", "Pl"}, {"pl", "Pl"},
            {"terrace", "Tce"}, {"tce", "Tce"},
            {"boulevard", "Bvd"}, {"boulevarde", "Bvd"}, {"blvd", "Bvd"}, {"bvd", "Bvd"},
            {"lane", "Ln"}, {"ln", "Ln"},
            {"close", "Cl"}, {"cl", "Cl"},
            {"circuit", "Cct"}, {"cct", "Cct"},
            {"square", "Sq"}, {"sq", "Sq"},
            {"esplanade", "Esp"}, {"esp", "Esp"},
            {"grove", "Gr"}, {"gr", "Gr"},
            {"parkway", "Pkwy"}, {"pkwy", "Pkwy"},
            {"freeway", "Fwy"}, {"fwy", "Fwy"},
            {"promenade", "Prom"},
            {"arcade", "Arc"},
            {"way", "Way"},
            {"mall", "Mall"},
        };
        for (String[] t : types) {
            STREET_TYPES.put(t[0], t[1]);
        }
    }

    private Normalizer() {
    }

    // ---------------------------------------------------------------- text

    /**
     * Basic clean-up for any text: HTML entities, broken encodings, odd quotes,
     * invisible characters and extra spaces. Returns null for blank input.
     */
    public static String text(String s) {
        if (s == null) {
            return null;
        }
        String t = Parser.unescapeEntities(s, false);
        t = fixMojibake(t);
        t = java.text.Normalizer.normalize(t, Form.NFKC);
        t = t.replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201A', '\'')
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
            .replace("\u2026", "...")
            .replace("\uFFFD", "");
        t = t.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "");
        t = SPACES.matcher(t).replaceAll(" ").strip();
        return t.isEmpty() ? null : t;
    }

    /** Undoes UTF-8 text that was decoded as Windows-1252 somewhere along the way ("â€™" becomes "'"). */
    static String fixMojibake(String s) {
        if (!MOJIBAKE.matcher(s).find()) {
            return s;
        }
        try {
            ByteBuffer bytes = CP1252.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(s));
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes)
                .toString();
        } catch (CharacterCodingException e) {
            return s;
        }
    }

    /** Clinic or suburb name: cleaned, and re-cased only when the source shouts or whispers. */
    public static String name(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        return isShouty(t) ? titleCase(t) : t;
    }

    static boolean isShouty(String s) {
        long letters = s.chars().filter(Character::isLetter).count();
        if (letters < 3) {
            return false;
        }
        return s.equals(s.toUpperCase(Locale.ROOT)) || s.equals(s.toLowerCase(Locale.ROOT));
    }

    public static String titleCase(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean start = true;
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ' || c == '-' || c == '/' || c == '(' || c == '\'';
        }
        return sb.toString();
    }

    public static String capitalise(String s) {
        String t = text(s);
        if (t == null) {
            return null;
        }
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ---------------------------------------------------------------- phone

    public record Phone(String national, String e164) {
    }

    /** Parses an Australian phone number. Returns null when it is not a valid number. */
    public static Phone phone(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        t = t.replaceFirst("(?i)^tel:", "");
        // Some listings hold two numbers; keep the first one.
        t = t.split("\\s*(?:/|,|;|\\bor\\b)\\s*")[0];
        try {
            PhoneNumber n = PHONES.parse(t, "AU");
            if (!PHONES.isValidNumber(n)) {
                return null;
            }
            return new Phone(PHONES.format(n, PhoneNumberUtil.PhoneNumberFormat.NATIONAL),
                PHONES.format(n, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return null;
        }
    }

    /** Landline area code for a state (Broken Hill and a few border towns differ, hence the flag). */
    static String areaCode(String state) {
        return switch (state) {
            case "NSW", "ACT" -> "02";
            case "VIC", "TAS" -> "03";
            case "QLD" -> "07";
            default -> "08";
        };
    }

    /** 13, 1300 and 1800 numbers are shared by whole chains, so they can't identify a clinic. */
    public static boolean isSharedNumber(String e164) {
        return e164 != null && (e164.startsWith("+6113") || e164.startsWith("+611800") || e164.startsWith("+611300"));
    }

    // ---------------------------------------------------------------- address

    /** Maps "Victoria", "Vic.", "vic" and friends to the official abbreviation. */
    public static String state(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        String key = t.toUpperCase(Locale.ROOT).replace(".", "").strip();
        return STATE_NAMES.get(key);
    }

    /** Four digit postcode; NT codes lose their leading zero in some sources, so 800 becomes 0800. */
    public static String postcode(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        String digits = t.replaceAll("\\D", "");
        if (digits.length() == 3) {
            digits = "0" + digits;
        }
        return digits.length() == 4 ? digits : null;
    }

    /** True when the postcode falls inside the ranges Australia Post gives to that state. */
    public static boolean postcodeInState(String postcode, String state) {
        if (postcode == null || state == null) {
            return true;
        }
        int pc = Integer.parseInt(postcode);
        return switch (state) {
            case "NSW" -> between(pc, 1000, 2599) || between(pc, 2619, 2899) || between(pc, 2921, 2999);
            case "ACT" -> between(pc, 200, 299) || between(pc, 2600, 2618) || between(pc, 2900, 2920);
            case "VIC" -> between(pc, 3000, 3999) || between(pc, 8000, 8999);
            case "QLD" -> between(pc, 4000, 4999) || between(pc, 9000, 9999);
            case "SA" -> between(pc, 5000, 5999);
            case "WA" -> between(pc, 6000, 6999);
            case "TAS" -> between(pc, 7000, 7999);
            case "NT" -> between(pc, 800, 999);
            default -> false;
        };
    }

    private static boolean between(int v, int lo, int hi) {
        return v >= lo && v <= hi;
    }

    /**
     * Cleans a street line: drops leading directions such as
     * "Parking entrance via Ellis Ave," and uses standard street type abbreviations.
     */
    public static String street(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        t = t.replaceAll("\\s*,\\s*", ", ").replaceAll(",\\s*$", "");
        String[] parts = t.split(", ");
        int firstNumbered = -1;
        for (int i = 0; i < parts.length; i++) {
            if (STARTS_WITH_NUMBER.matcher(parts[i]).find()) {
                firstNumbered = i;
                break;
            }
        }
        if (firstNumbered > 0) {
            boolean leadingHasDigits = false;
            for (int i = 0; i < firstNumbered; i++) {
                leadingHasDigits |= parts[i].matches(".*\\d.*");
            }
            if (!leadingHasDigits) {
                t = String.join(", ", List.of(parts).subList(firstNumbered, parts.length));
            }
        }
        if (isShouty(t)) {
            t = titleCase(t);
        }
        return abbreviateStreetTypes(t);
    }

    public static String abbreviateStreetTypes(String street) {
        String[] words = street.split(" ");
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            String trailing = w.endsWith(",") ? "," : "";
            String bare = w.replaceAll("[.,]+$", "").toLowerCase(Locale.ROOT);
            String abbr = STREET_TYPES.get(bare);
            if (abbr == null) {
                continue;
            }
            boolean last = i == words.length - 1;
            boolean beforeJoin = !last && Set.of("&", "and", "cnr", "corner").contains(words[i + 1].toLowerCase(Locale.ROOT));
            if (last || beforeJoin || !trailing.isEmpty()) {
                words[i] = abbr + trailing;
            }
        }
        return String.join(" ", words);
    }

    /** Australia Post abbreviation for a street type word ("road" gives "Rd"), or null. */
    public static String streetTypeAbbreviation(String word) {
        return word == null ? null : STREET_TYPES.get(word.replaceAll("[.,]+$", "").toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- web

    /** Cleans a URL: lowercase host, no tracking parameters, no fragment. Returns null if it isn't a URL. */
    public static String url(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        if (!t.matches("(?i)^https?://.*")) {
            if (t.contains(" ") || !t.contains(".")) {
                return null;
            }
            t = "http://" + t.replaceFirst("^/+", "");
        }
        Matcher m = URL.matcher(t);
        if (!m.matches()) {
            return null;
        }
        String scheme = m.group(1).toLowerCase(Locale.ROOT);
        String host = m.group(2).toLowerCase(Locale.ROOT);
        String path = m.group(3);
        String query = m.group(4);
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (path != null && !path.equals("/")) {
            sb.append(path);
        }
        if (query != null) {
            List<String> kept = new ArrayList<>();
            for (String p : query.substring(1).split("&")) {
                String name = p.split("=", 2)[0].toLowerCase(Locale.ROOT);
                if (!p.isEmpty() && !name.startsWith("utm_") && !Set.of("gclid", "fbclid", "_ga", "mc_cid", "mc_eid").contains(name)) {
                    kept.add(p);
                }
            }
            if (!kept.isEmpty()) {
                if (path == null || path.isEmpty() || path.equals("/")) {
                    sb.append('/');
                }
                sb.append('?').append(String.join("&", kept));
            }
        }
        return sb.toString();
    }

    public static String email(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        t = t.replaceFirst("(?i)^mailto:", "").split("\\?")[0].strip().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(t).matches() ? t : null;
    }

    /** Decodes a Cloudflare-protected email: the first byte is an XOR key for the rest. */
    public static String cloudflareEmail(String hex) {
        if (hex == null || hex.length() < 4 || hex.length() % 2 != 0) {
            return null;
        }
        try {
            byte[] bytes = HexFormat.of().parseHex(hex);
            int key = bytes[0] & 0xff;
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < bytes.length; i++) {
                sb.append((char) ((bytes[i] & 0xff) ^ key));
            }
            return email(sb.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "facebook", "instagram", "other" for other social networks, or null for a normal website. */
    public static String socialKind(String url) {
        if (url == null) {
            return null;
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("/sharer") || u.contains("/share?") || u.contains("/intent/")) {
            return null;
        }
        String host = u.replaceFirst("^https?://", "").split("[/?#]")[0];
        if (host.endsWith("facebook.com") || host.equals("fb.com") || host.equals("fb.me")) {
            return "facebook";
        }
        if (host.endsWith("instagram.com")) {
            return "instagram";
        }
        if (host.endsWith("youtube.com") || host.equals("youtu.be") || host.endsWith("linkedin.com")
            || host.endsWith("twitter.com") || host.equals("x.com") || host.endsWith("tiktok.com")
            || host.contains("pinterest.")) {
            return "other";
        }
        return null;
    }

    // ---------------------------------------------------------------- numbers

    /** Rounds a coordinate to 6 decimals (about 10 cm), which removes float noise. */
    public static String coordinate(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            return new BigDecimal(t).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean insideAustralia(Double lat, Double lng) {
        return lat != null && lng != null && lat > -44.5 && lat < -9 && lng > 112 && lng < 154.5;
    }

    public static String rating(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(t);
            return v > 0 && v <= 5 ? String.format(Locale.ROOT, "%.1f", v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String integer(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        String digits = t.replaceAll("[,\\s]", "");
        return digits.matches("\\d+") ? String.valueOf(Long.parseLong(digits)) : null;
    }

    // ---------------------------------------------------------------- lists

    /** Joins items with "; ", dropping blanks and case-insensitive duplicates. */
    public static String list(List<String> items) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String item : items) {
            String t = text(item);
            if (t != null) {
                unique.putIfAbsent(t.toLowerCase(Locale.ROOT), t);
            }
        }
        return unique.isEmpty() ? null : String.join("; ", unique.values());
    }

    public static List<String> splitList(String value) {
        if (value == null) {
            return List.of();
        }
        return List.of(value.split("\\s*;\\s*"));
    }

    /** "Dogs, cats + birds & exotics" becomes "Dogs, cats, birds & exotics". */
    public static String species(String raw) {
        String t = text(raw);
        if (t == null) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (String part : t.split("\\s*(?:,|\\+|;|/|\\band\\b)\\s*")) {
            if (!part.isBlank()) {
                String item = part.strip().toLowerCase(Locale.ROOT);
                if (!items.contains(item)) {
                    items.add(item);
                }
            }
        }
        return items.isEmpty() ? null : capitalise(String.join(", ", items));
    }

    // ---------------------------------------------------------------- whole record

    /**
     * Cleans every field of a freshly scraped record in place and flags anything suspicious.
     * The original values are kept in {@link SourceRecord#raw()}.
     */
    public static void apply(SourceRecord r) {
        r.values().forEach((f, v) -> r.raw().putIfAbsent(f.column(), v));

        r.set(Field.NAME, name(r.get(Field.NAME)));
        r.set(Field.CATEGORY, capitalise(r.get(Field.CATEGORY)));
        r.set(Field.STREET, street(r.get(Field.STREET)));
        r.set(Field.SUBURB, name(r.get(Field.SUBURB)));

        String rawState = r.get(Field.STATE);
        String state = state(rawState);
        if (rawState != null && state == null) {
            r.flag("BAD_STATE");
        }
        r.set(Field.STATE, state);

        String rawPostcode = r.get(Field.POSTCODE);
        String postcode = postcode(rawPostcode);
        if (rawPostcode != null && postcode == null) {
            r.flag("BAD_POSTCODE");
        }
        r.set(Field.POSTCODE, postcode);
        if (!postcodeInState(postcode, state)) {
            r.flag("POSTCODE_STATE_MISMATCH");
        }

        r.set(Field.LATITUDE, coordinate(r.get(Field.LATITUDE)));
        r.set(Field.LONGITUDE, coordinate(r.get(Field.LONGITUDE)));
        if ((r.has(Field.LATITUDE) || r.has(Field.LONGITUDE)) && !insideAustralia(r.latitude(), r.longitude())) {
            r.flag("BAD_COORDINATES");
            r.set(Field.LATITUDE, (String) null);
            r.set(Field.LONGITUDE, (String) null);
        }

        String rawPhone = r.get(Field.PHONE);
        Phone phone = phone(rawPhone);
        if (phone == null && rawPhone != null && state != null && rawPhone.replaceAll("\\D", "").length() == 8) {
            // A landline without its area code: the code follows from the state.
            phone = phone(areaCode(state) + rawPhone);
            if (phone != null) {
                r.flag("PHONE_AREA_CODE_ADDED");
            }
        }
        if (rawPhone != null && phone == null) {
            r.flag("BAD_PHONE");
        }
        r.set(Field.PHONE, phone == null ? null : phone.national());

        // A "website" that is really a Facebook page goes in the Facebook column.
        String website = url(r.get(Field.WEBSITE));
        String kind = socialKind(website);
        if (kind != null) {
            if (kind.equals("facebook") && !r.has(Field.FACEBOOK)) {
                r.set(Field.FACEBOOK, website);
            } else if (kind.equals("instagram") && !r.has(Field.INSTAGRAM)) {
                r.set(Field.INSTAGRAM, website);
            }
            website = null;
        }
        r.set(Field.WEBSITE, website);
        r.set(Field.FACEBOOK, url(r.get(Field.FACEBOOK)));
        r.set(Field.INSTAGRAM, url(r.get(Field.INSTAGRAM)));
        r.set(Field.OTHER_SOCIALS, list(splitList(r.get(Field.OTHER_SOCIALS)).stream().map(Normalizer::url).toList()));
        r.set(Field.BOOKING_URL, url(r.get(Field.BOOKING_URL)));
        r.set(Field.GOOGLE_MAPS_URL, url(r.get(Field.GOOGLE_MAPS_URL)));
        r.set(Field.EMAIL, email(r.get(Field.EMAIL)));

        r.set(Field.SPECIES_TREATED, species(r.get(Field.SPECIES_TREATED)));
        r.set(Field.GOOGLE_RATING, rating(r.get(Field.GOOGLE_RATING)));
        r.set(Field.DIRECTORY_RATING, rating(r.get(Field.DIRECTORY_RATING)));
        r.set(Field.GOOGLE_REVIEW_COUNT, integer(r.get(Field.GOOGLE_REVIEW_COUNT)));
        r.set(Field.NUMBER_OF_VETS, integer(r.get(Field.NUMBER_OF_VETS)));
        r.set(Field.NUMBER_OF_STAFF, integer(r.get(Field.NUMBER_OF_STAFF)));

        for (Field f : Field.values()) {
            String v = r.get(f);
            if (v == null) {
                continue;
            }
            if (f.isYesNo()) {
                r.set(f, v.equalsIgnoreCase("Y") ? "Y" : v.equalsIgnoreCase("N") ? "N" : null);
            } else if (f.isList()) {
                r.set(f, list(splitList(v)));
            } else if (f == Field.OPENING_HOURS) {
                r.set(f, text(v));
            }
        }
    }
}
