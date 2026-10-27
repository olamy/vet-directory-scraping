package vetdb.enrich;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vetdb.model.Enrichment;
import vetdb.model.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses a fake lookup, so no ABR_GUID or network is needed. */
class AbnCsvTest {

    private static final String HEADER = "﻿clinic_id,Clinic name,Clinic full address,Street Address,Suburb,State,Postcode,Phone";
    private static final String INPUT = HEADER + "\r\n"
        + "1,Lakeside Veterinary Hospital,\"Shop 2, 5 Lake Rd Lakeside NSW 2200\",\"Shop 2, 5 Lake Rd\",Lakeside,NSW,2200,(02) 9000 0004\r\n"
        + "2,Darwin Vet,1 Smith St Darwin NT 800,1 Smith St,Darwin,Northern Territory,800,\r\n";

    @TempDir
    Path dir;

    private final List<String> asked = new ArrayList<>();

    /** Knows the Lakeside clinic, finds two Darwin Vets, and remembers what it was asked. */
    private final AbnCsv.Lookup lookup = (key, name, postcode, state) -> {
        asked.add(key + "|" + name + "|" + postcode + "|" + state);
        if (name.startsWith("Lakeside")) {
            return new AbrClient.Result(new Enrichment("abr", key, "MATCHED", Map.of(Field.ABN, "74 172 177 893",
                Field.REGISTERED_NAME, "LAKESIDE VETERINARY HOSPITAL PTY LTD", Field.ABN_STATUS, "Active")), List.of());
        }
        return new AbrClient.Result(new Enrichment("abr", key, "AMBIGUOUS", Map.of()),
            List.of("Darwin Vet (11 111 111 111)", "DARWIN VET PTY LTD (22 222 222 222)"));
    };

    @Test
    void addsTheAbnColumnsToEveryRow() throws Exception {
        Path in = dir.resolve("clinics.csv");
        Path out = dir.resolve("clinics-abn.csv");
        Files.writeString(in, INPUT);

        assertEquals(Map.of("AMBIGUOUS", 1, "MATCHED", 1), AbnCsv.enrich(in, out, lookup, 0));
        // Postcode and state are cleaned before the lookup.
        assertEquals(List.of("1|Lakeside Veterinary Hospital|2200|NSW", "2|Darwin Vet|0800|NT"), asked);
        assertEquals(HEADER + ",ABN,ABN registered name,ABN status,ABN match,ABN candidates\r\n"
                + "1,Lakeside Veterinary Hospital,\"Shop 2, 5 Lake Rd Lakeside NSW 2200\",\"Shop 2, 5 Lake Rd\",Lakeside,NSW,2200,"
                + "(02) 9000 0004,74 172 177 893,LAKESIDE VETERINARY HOSPITAL PTY LTD,Active,MATCHED,\r\n"
                + "2,Darwin Vet,1 Smith St Darwin NT 800,1 Smith St,Darwin,Northern Territory,800,,,,,AMBIGUOUS,"
                + "Darwin Vet (11 111 111 111); DARWIN VET PTY LTD (22 222 222 222)\r\n",
            Files.readString(out));
        assertEquals(INPUT, Files.readString(in));
    }

    @Test
    void runningAgainReplacesTheColumnsAndLimitKeepsTheOtherRows() throws Exception {
        Path csv = dir.resolve("clinics.csv");
        Files.writeString(csv, INPUT);
        AbnCsv.enrich(csv, csv, lookup, 0);
        String first = Files.readString(csv);
        asked.clear();

        AbnCsv.enrich(csv, csv, lookup, 1);
        assertEquals(1, asked.size());
        assertEquals(first, Files.readString(csv));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp file left behind");
        }
    }

    @Test
    void missingColumnLeavesTheFileAlone() throws Exception {
        Path csv = dir.resolve("other.csv");
        Files.writeString(csv, "id,name\n1,Lakeside\n");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AbnCsv.enrich(csv, csv, lookup, 0));
        assertTrue(e.getMessage().contains("Clinic name"), e.getMessage());
        assertEquals("id,name\n1,Lakeside\n", Files.readString(csv));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temp file left behind");
        }
    }
}
