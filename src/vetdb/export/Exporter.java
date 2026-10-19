package vetdb.export;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import vetdb.model.Clinic;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/** Writes the merged clinics (and the raw records) to CSV files and one Excel workbook. */
public final class Exporter {

    private static final List<String> SOURCES = List.of("ratemyvet", "bestforpet", "pia", "vetnet");
    private static final Set<Field> NUMERIC = Set.of(Field.LATITUDE, Field.LONGITUDE, Field.GOOGLE_REVIEW_COUNT,
        Field.GOOGLE_RATING, Field.DIRECTORY_RATING, Field.NUMBER_OF_VETS, Field.NUMBER_OF_STAFF);
    /** Flags worth a human look even when the clinic is otherwise fine. */
    private static final List<String> REVIEW_FLAGS = List.of("SAME_ADDRESS_AS_", "SEVERAL_LISTINGS_SAME_SOURCE",
        "POSTCODE_STATE_MISMATCH", "ABN_CANCELLED", "ABN_AMBIGUOUS", "CLOSED_TEMPORARILY", "NO_STREET_ADDRESS");

    private Exporter() {
    }

    public record Summary(int clinics, int needsReview, int rejected) {
    }

    public static Summary export(Path outDir, List<Clinic> clinics, List<SourceRecord> records) throws IOException {
        Files.createDirectories(outDir);
        List<Clinic> kept = clinics.stream().filter(c -> c.verification() != Clinic.Verification.REJECTED).toList();
        List<Clinic> review = kept.stream().filter(c -> reviewReason(c) != null).toList();
        List<Clinic> rejected = clinics.stream().filter(c -> c.verification() == Clinic.Verification.REJECTED).toList();

        List<String> header = clinicHeader();
        writeCsv(outDir.resolve("clinics.csv"), header, kept.stream().map(Exporter::clinicRow).toList());
        writeCsv(outDir.resolve("needs_review.csv"), with(header, "review_reason"),
            review.stream().map(c -> with(clinicRow(c), reviewReason(c))).toList());
        writeCsv(outDir.resolve("rejected.csv"), with(header, "reject_reason"),
            rejected.stream().map(c -> with(clinicRow(c), c.rejectReason())).toList());
        writeCsv(outDir.resolve("source_records.csv"), recordHeader(), records.stream().map(Exporter::recordRow).toList());

        try (OutputStream os = Files.newOutputStream(outDir.resolve("clinics.xlsx"))) {
            Workbook wb = new Workbook(os, "VetDb", "1.0");
            writeSheet(wb.newWorksheet("Clinics"), header, kept.stream().map(Exporter::clinicRow).toList());
            writeSheet(wb.newWorksheet("Needs review"), with(header, "review_reason"),
                review.stream().map(c -> with(clinicRow(c), reviewReason(c))).toList());
            writeSheet(wb.newWorksheet("Rejected"), with(header, "reject_reason"),
                rejected.stream().map(c -> with(clinicRow(c), c.rejectReason())).toList());
            writeSheet(wb.newWorksheet("Raw by source"), recordHeader(), records.stream().map(Exporter::recordRow).toList());
            wb.finish();
        }
        return new Summary(kept.size(), review.size(), rejected.size());
    }

    /** Why a kept clinic should be looked at, or null when it looks fine. */
    static String reviewReason(Clinic c) {
        List<String> reasons = new ArrayList<>();
        if (c.verification() == Clinic.Verification.SINGLE_SOURCE) {
            reasons.add("only in " + c.sources().getFirst());
        }
        for (String flag : c.flags()) {
            if (REVIEW_FLAGS.stream().anyMatch(flag::startsWith)) {
                reasons.add(flag);
            }
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    static List<String> clinicHeader() {
        List<String> h = new ArrayList<>();
        h.add("clinic_id");
        for (Field f : Field.values()) {
            h.add(f.column());
        }
        SOURCES.forEach(s -> h.add(s + "_url"));
        h.add("sources");
        h.add("verification_status");
        h.add("flags");
        return h;
    }

    static List<String> clinicRow(Clinic c) {
        List<String> row = new ArrayList<>();
        row.add(String.valueOf(c.id()));
        for (Field f : Field.values()) {
            row.add(c.get(f));
        }
        SOURCES.forEach(s -> row.add(c.profileUrl(s)));
        row.add(String.join("; ", c.sources()));
        row.add(c.verification().name().toLowerCase());
        row.add(String.join("; ", c.flags()));
        return row;
    }

    private static List<String> recordHeader() {
        List<String> h = new ArrayList<>(List.of("source", "source_id", "profile_url", "source_modified"));
        for (Field f : Field.values()) {
            h.add(f.column());
        }
        h.add("flags");
        return h;
    }

    private static List<String> recordRow(SourceRecord r) {
        List<String> row = new ArrayList<>(List.of(r.source(), r.sourceId(), r.profileUrl(),
            r.sourceModified() == null ? "" : r.sourceModified()));
        for (Field f : Field.values()) {
            row.add(r.get(f));
        }
        row.add(String.join("; ", r.flags()));
        return row;
    }

    private static List<String> with(List<String> list, String extra) {
        List<String> copy = new ArrayList<>(list);
        copy.add(extra);
        return copy;
    }

    private static void writeCsv(Path file, List<String> header, List<List<String>> rows) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // The BOM tells Excel the file is UTF-8, otherwise accents come out garbled.
            w.write('﻿');
            try (CSVPrinter csv = new CSVPrinter(w, CSVFormat.DEFAULT)) {
                csv.printRecord(header);
                for (List<String> row : rows) {
                    csv.printRecord(row.stream().map(v -> v == null ? "" : v).collect(Collectors.toList()));
                }
            }
        }
    }

    private static void writeSheet(Worksheet ws, List<String> header, List<List<String>> rows) {
        for (int c = 0; c < header.size(); c++) {
            ws.value(0, c, header.get(c));
            ws.style(0, c).bold().set();
        }
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String v = row.get(c);
                if (v == null || v.isEmpty()) {
                    continue;
                }
                if (isNumericColumn(header.get(c))) {
                    try {
                        ws.value(r + 1, c, Double.parseDouble(v));
                        continue;
                    } catch (NumberFormatException ignored) {
                        // Not a number after all; write it as text.
                    }
                }
                ws.value(r + 1, c, v);
            }
        }
        ws.freezePane(1, 1);
    }

    private static boolean isNumericColumn(String column) {
        return column.equals("clinic_id") || NUMERIC.stream().anyMatch(f -> f.column().equals(column));
    }
}
