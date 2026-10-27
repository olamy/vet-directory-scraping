package vetdb.enrich;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import vetdb.clean.Normalizer;
import vetdb.model.Enrichment;
import vetdb.model.Field;

/**
 * Adds ABN columns to a CSV of clinics, looking each row up by its "Clinic name", "Postcode" and "State".
 * ABN columns left by an earlier run get replaced, so running it again on the same file is fine.
 * When a row is AMBIGUOUS, "ABN candidates" lists the names and ABNs that tied, to pick from by hand.
 */
public final class AbnCsv {

    public static final List<String> COLUMNS = List.of("ABN", "ABN registered name", "ABN status", "ABN match", "ABN candidates");
    private static final String BOM = "﻿";

    /** One ABN lookup, normally {@link AbrClient#lookup(String, String, String, String)}. */
    @FunctionalInterface
    public interface Lookup {
        AbrClient.Result find(String key, String name, String postcode, String state);
    }

    private AbnCsv() {
    }

    /**
     * Reads {@code in}, looks every row up and writes the result to {@code out}, which can be the same file.
     * With a limit only the first rows are looked up; the others keep the ABN values they already had.
     * Returns how many rows came out MATCHED, NOT_FOUND, AMBIGUOUS or ERROR.
     */
    public static Map<String, Integer> enrich(Path in, Path out, Lookup lookup, int limit) throws IOException {
        String content = Files.readString(in, StandardCharsets.UTF_8);
        boolean bom = content.startsWith(BOM);
        if (bom) {
            content = content.substring(1);
        }
        String lineEnd = content.contains("\r\n") ? "\r\n" : "\n";
        Map<String, Integer> outcomes = new TreeMap<>();

        // Write next to the target first, so a failed run never leaves a half written file behind.
        Path tmp = Files.createTempFile(out.toAbsolutePath().getParent(), ".abn-", ".csv");
        try {
            try (CSVParser parser = CSVParser.parse(content, CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build());
                 Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                List<String> header = parser.getHeaderNames();
                String idColumn = find(header, "clinic_id");
                String nameColumn = require(header, "Clinic name");
                String postcodeColumn = require(header, "Postcode");
                String stateColumn = require(header, "State");
                List<String> kept = header.stream().filter(h -> find(COLUMNS, h) == null).toList();

                if (bom) {
                    w.write(BOM);
                }
                CSVPrinter csv = new CSVPrinter(w, CSVFormat.DEFAULT.builder().setRecordSeparator(lineEnd).build());
                List<String> newHeader = new ArrayList<>(kept);
                newHeader.addAll(COLUMNS);
                csv.printRecord(newHeader);

                int looked = 0;
                for (CSVRecord row : parser) {
                    List<String> values = new ArrayList<>();
                    kept.forEach(h -> values.add(value(row, h)));
                    if (limit > 0 && looked >= limit) {
                        COLUMNS.forEach(c -> values.add(value(row, find(header, c))));
                    } else {
                        String key = idColumn == null ? String.valueOf(row.getRecordNumber()) : value(row, idColumn);
                        String name = value(row, nameColumn);
                        AbrClient.Result found = lookup.find(key, name.isBlank() ? null : name.strip(),
                            Normalizer.postcode(value(row, postcodeColumn)), Normalizer.state(value(row, stateColumn)));
                        values.addAll(columns(found));
                        outcomes.merge(found.enrichment().status(), 1, Integer::sum);
                        if (++looked % 100 == 0) {
                            System.out.printf("[abn] %d looked up %s%n", looked, outcomes);
                        }
                    }
                    csv.printRecord(values);
                }
                csv.flush();
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return outcomes;
    }

    /** The values that go in the ABN columns, in the same order as {@link #COLUMNS}. */
    static List<String> columns(AbrClient.Result found) {
        Enrichment e = found.enrichment();
        Map<Field, String> v = e.values();
        return List.of(v.getOrDefault(Field.ABN, ""), v.getOrDefault(Field.REGISTERED_NAME, ""),
            v.getOrDefault(Field.ABN_STATUS, ""), e.status(), String.join("; ", found.candidates()));
    }

    /** The header called {@code name}, ignoring case and spaces around it, or null. */
    private static String find(List<String> header, String name) {
        return header.stream().filter(h -> h.strip().equalsIgnoreCase(name.strip())).findFirst().orElse(null);
    }

    private static String require(List<String> header, String name) {
        String h = find(header, name);
        if (h == null) {
            throw new IllegalArgumentException("the CSV has no \"" + name + "\" column, found " + header);
        }
        return h;
    }

    private static String value(CSVRecord row, String column) {
        return column != null && row.isSet(column) ? row.get(column) : "";
    }
}
