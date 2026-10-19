package vetdb.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import vetdb.clean.Normalizer;
import vetdb.http.Fetcher;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/**
 * vet.net.au: an ExpressionEngine site. The sitemap lists every clinic profile
 * (/vet-detail/.../A12345678) and each profile is plain server-side HTML.
 */
public final class VetNetSource implements Source {

    public static final String NAME = "vetnet";
    private static final String BASE = "https://www.vet.net.au";
    private static final Pattern ID = Pattern.compile("/(A\\d{8})\\b");
    private static final Pattern SUBURB_LINE = Pattern.compile("^(.+?)\\s+(ACT|NSW|NT|QLD|SA|TAS|VIC|WA)\\s+(\\d{3,4})$");
    private static final Pattern MAP_Q = Pattern.compile("[?&]q=(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String host() {
        return "www.vet.net.au";
    }

    @Override
    public long delayMillis() {
        return 1000;
    }

    @Override
    public void run(Fetcher fetcher, int limit, Consumer<SourceRecord> sink) {
        // The sitemap has a few duplicate profile URLs; the A-number is what identifies a clinic.
        Map<String, String> byId = new LinkedHashMap<>();
        for (String loc : Extract.sitemapLocs(fetcher.get(BASE + "/sitemap.xml"))) {
            if (loc.contains("/vet-detail/")) {
                String id = Extract.group(ID, loc, 1);
                if (id != null) {
                    byId.putIfAbsent(id, loc.replaceFirst("^http://", "https://"));
                }
            }
        }
        List<String> urls = new ArrayList<>(byId.values());
        int total = limit > 0 ? Math.min(limit, urls.size()) : urls.size();
        System.out.printf("[%s] %d clinic profiles in the sitemap, processing %d%n", NAME, urls.size(), total);
        for (int i = 0; i < total; i++) {
            String url = urls.get(i);
            String html = fetcher.get(url);
            if (html == null) {
                System.err.printf("[%s] could not get %s%n", NAME, url);
                continue;
            }
            SourceRecord r = parse(url, html);
            if (r != null) {
                sink.accept(r);
            }
            if ((i + 1) % 100 == 0) {
                System.out.printf("[%s] %d/%d%n", NAME, i + 1, total);
            }
        }
    }

    /** Returns null for the "not found" stub the site serves for unknown IDs. */
    public static SourceRecord parse(String url, String html) {
        Document doc = Jsoup.parse(html, url);
        Element h1 = doc.selectFirst(".dent-name h1");
        String id = Extract.group(ID, url, 1);
        if (h1 == null || id == null) {
            return null;
        }
        SourceRecord r = new SourceRecord(NAME, id, url);
        r.set(Field.NAME, h1.text());

        // Jsoup drops HTML comments, so the commented-out copies of these blocks don't get in the way.
        for (Element section : doc.select("section.detail_sec:has(img[src$=address.png])")) {
            List<Element> lines = section.select(".sec_detail p");
            if (lines.size() >= 2) {
                r.set(Field.STREET, lines.get(0).text());
                Matcher m = SUBURB_LINE.matcher(lines.get(1).text().strip());
                if (m.matches()) {
                    r.set(Field.SUBURB, m.group(1));
                    r.set(Field.STATE, m.group(2));
                    r.set(Field.POSTCODE, m.group(3));
                } else {
                    r.raw().put("suburb_line", lines.get(1).text());
                    r.flag("UNPARSED_SUBURB_LINE");
                }
                break;
            }
        }

        Element call = doc.selectFirst("a.callus-btn[href^=tel:]");
        r.set(Field.PHONE, call == null ? null : call.attr("href").replaceFirst("^tel:", ""));
        r.set(Field.WEBSITE, Extract.attr(doc.selectFirst("a[logger_field=schurl]"), "data"));

        List<String> others = new ArrayList<>();
        for (Element a : doc.select("section.social-link a[href]")) {
            String link = a.attr("href");
            String kind = Normalizer.socialKind(link);
            if ("facebook".equals(kind) && !r.has(Field.FACEBOOK)) {
                r.set(Field.FACEBOOK, link);
            } else if ("instagram".equals(kind) && !r.has(Field.INSTAGRAM)) {
                r.set(Field.INSTAGRAM, link);
            } else if ("other".equals(kind)) {
                others.add(link);
            }
        }
        r.set(Field.OTHER_SOCIALS, String.join("; ", others));

        List<String> services = new ArrayList<>();
        Element servicesSection = doc.selectFirst("section.treatments:has(h3:containsOwn(Services))");
        if (servicesSection != null) {
            servicesSection.select("span").forEach(s -> services.add(s.text()));
        }
        r.set(Field.SERVICES, String.join("; ", services));

        Element hoursHeading = doc.selectFirst(".accdion-head h3:matchesOwn(^\\s*Opening Hours\\s*$)");
        Element hoursBox = hoursHeading == null ? null : hoursHeading.closest(".accdion");
        if (hoursBox != null) {
            List<String> days = new ArrayList<>();
            for (Element li : hoursBox.select(".accdion-content li")) {
                days.add(Extract.hoursEntry(Extract.text(li.selectFirst("span")), li.ownText()));
            }
            r.set(Field.OPENING_HOURS, String.join("; ", days));
        }

        String mapSrc = Extract.attr(doc.selectFirst("iframe.embed_custom_map"), "src");
        Matcher q = MAP_Q.matcher(mapSrc == null ? "" : mapSrc);
        if (q.find()) {
            r.set(Field.LATITUDE, q.group(1));
            r.set(Field.LONGITUDE, q.group(2));
        }

        Map<String, String> staff = new LinkedHashMap<>();
        for (Element row : doc.select("#staff-sec .info-row")) {
            String name = Extract.text(row.selectFirst(".ct-name"));
            String position = Extract.text(row.selectFirst(".ct-position"));
            if (name != null) {
                staff.putIfAbsent(name, position == null ? null : position.split("\\s+in\\s+")[0]);
            }
        }
        if (!staff.isEmpty()) {
            r.set(Field.NUMBER_OF_STAFF, staff.size());
            r.set(Field.NUMBER_OF_VETS, staff.values().stream().filter(Extract::isVetRole).count());
        }
        return r;
    }
}
