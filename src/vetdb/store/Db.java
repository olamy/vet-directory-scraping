package vetdb.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import vetdb.model.Clinic;
import vetdb.model.Enrichment;
import vetdb.model.Field;
import vetdb.model.SourceRecord;

/** SQLite storage: raw records per source, merged clinics, and ABR / Google lookups. */
public final class Db implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIELD_COLUMNS = Stream.of(Field.values())
        .map(f -> f.column() + " TEXT").collect(Collectors.joining(", "));

    private final Connection conn;

    public Db(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new SQLException("cannot create " + file.getParent(), e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS source_record (source TEXT NOT NULL, source_id TEXT NOT NULL, "
                + "profile_url TEXT, source_modified TEXT, country TEXT, fetched_at TEXT, " + FIELD_COLUMNS
                + ", flags TEXT, raw_json TEXT, PRIMARY KEY (source, source_id))");
            st.execute("CREATE TABLE IF NOT EXISTS clinic (clinic_id INTEGER PRIMARY KEY, " + FIELD_COLUMNS
                + ", ratemyvet_url TEXT, bestforpet_url TEXT, pia_url TEXT, vetnet_url TEXT, sources TEXT, "
                + "verification_status TEXT, reject_reason TEXT, flags TEXT, provenance TEXT, anchor_key TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS clinic_member (clinic_id INTEGER, source TEXT, source_id TEXT, match_rule TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS enrichment (kind TEXT NOT NULL, member_key TEXT NOT NULL, status TEXT, "
                + "values_json TEXT, updated_at TEXT, PRIMARY KEY (kind, member_key))");
        }
    }

    // ------------------------------------------------------------------ source records

    /** Replaces everything we hold for one source with a fresh set of records. */
    public void replaceSourceRecords(String source, List<SourceRecord> records) throws SQLException {
        String columns = Stream.of(Field.values()).map(Field::column).collect(Collectors.joining(", "));
        String marks = Stream.of(Field.values()).map(f -> "?").collect(Collectors.joining(", "));
        String now = Instant.now().toString();
        conn.setAutoCommit(false);
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM source_record WHERE source = ?");
             PreparedStatement ins = conn.prepareStatement("INSERT OR REPLACE INTO source_record (source, source_id, "
                 + "profile_url, source_modified, country, fetched_at, " + columns + ", flags, raw_json) VALUES (?, ?, ?, ?, ?, ?, "
                 + marks + ", ?, ?)")) {
            del.setString(1, source);
            del.executeUpdate();
            for (SourceRecord r : records) {
                int i = 1;
                ins.setString(i++, r.source());
                ins.setString(i++, r.sourceId());
                ins.setString(i++, r.profileUrl());
                ins.setString(i++, r.sourceModified());
                ins.setString(i++, r.country());
                ins.setString(i++, now);
                for (Field f : Field.values()) {
                    ins.setString(i++, r.get(f));
                }
                ins.setString(i++, String.join(";", r.flags()));
                ins.setString(i, toJson(r.raw()));
                ins.addBatch();
            }
            ins.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public List<SourceRecord> loadSourceRecords() throws SQLException {
        List<SourceRecord> records = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM source_record ORDER BY source, source_id")) {
            while (rs.next()) {
                SourceRecord r = new SourceRecord(rs.getString("source"), rs.getString("source_id"), rs.getString("profile_url"));
                r.sourceModified(rs.getString("source_modified"));
                r.country(rs.getString("country"));
                for (Field f : Field.values()) {
                    r.set(f, rs.getString(f.column()));
                }
                String flags = rs.getString("flags");
                if (flags != null && !flags.isBlank()) {
                    for (String flag : flags.split(";")) {
                        r.flag(flag);
                    }
                }
                Map<String, String> raw = fromJson(rs.getString("raw_json"), new TypeReference<>() { });
                if (raw != null) {
                    r.raw().putAll(raw);
                }
                records.add(r);
            }
        }
        return records;
    }

    // ------------------------------------------------------------------ clinics

    public void saveClinics(List<Clinic> clinics) throws SQLException {
        String columns = Stream.of(Field.values()).map(Field::column).collect(Collectors.joining(", "));
        String marks = Stream.of(Field.values()).map(f -> "?").collect(Collectors.joining(", "));
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement();
             PreparedStatement ins = conn.prepareStatement("INSERT INTO clinic (clinic_id, " + columns
                 + ", ratemyvet_url, bestforpet_url, pia_url, vetnet_url, sources, verification_status, reject_reason, "
                 + "flags, provenance, anchor_key) VALUES (?, " + marks + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
             PreparedStatement member = conn.prepareStatement(
                 "INSERT INTO clinic_member (clinic_id, source, source_id, match_rule) VALUES (?, ?, ?, ?)")) {
            st.executeUpdate("DELETE FROM clinic");
            st.executeUpdate("DELETE FROM clinic_member");
            for (Clinic c : clinics) {
                int i = 1;
                ins.setInt(i++, c.id());
                for (Field f : Field.values()) {
                    ins.setString(i++, c.get(f));
                }
                for (String source : List.of("ratemyvet", "bestforpet", "pia", "vetnet")) {
                    ins.setString(i++, c.profileUrl(source));
                }
                ins.setString(i++, String.join(";", c.sources()));
                ins.setString(i++, c.verification().name());
                ins.setString(i++, c.rejectReason());
                ins.setString(i++, String.join(";", c.flags()));
                Map<String, String> provenance = new LinkedHashMap<>();
                c.provenance().forEach((f, s) -> provenance.put(f.column(), s));
                ins.setString(i++, toJson(provenance));
                ins.setString(i, c.anchor().key());
                ins.addBatch();
                for (SourceRecord m : c.members()) {
                    member.setInt(1, c.id());
                    member.setString(2, m.source());
                    member.setString(3, m.sourceId());
                    member.setString(4, c.matchRules().get(m.key()));
                    member.addBatch();
                }
            }
            ins.executeBatch();
            member.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    // ------------------------------------------------------------------ enrichment

    public void saveEnrichment(Enrichment e) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO enrichment "
            + "(kind, member_key, status, values_json, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            Map<String, String> values = new LinkedHashMap<>();
            e.values().forEach((f, v) -> values.put(f.column(), v));
            ps.setString(1, e.kind());
            ps.setString(2, e.memberKey());
            ps.setString(3, e.status());
            ps.setString(4, toJson(values));
            ps.setString(5, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** Lookups by kind ("abr", "google"), then by member key. */
    public Map<String, Map<String, Enrichment>> loadEnrichment() throws SQLException {
        Map<String, Map<String, Enrichment>> all = new HashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT kind, member_key, status, values_json FROM enrichment")) {
            while (rs.next()) {
                Map<String, String> raw = fromJson(rs.getString("values_json"), new TypeReference<>() { });
                Map<Field, String> values = new EnumMap<>(Field.class);
                if (raw != null) {
                    raw.forEach((k, v) -> values.put(Field.valueOf(k.toUpperCase()), v));
                }
                Enrichment e = new Enrichment(rs.getString("kind"), rs.getString("member_key"), rs.getString("status"), values);
                all.computeIfAbsent(e.kind(), k -> new HashMap<>()).put(e.memberKey(), e);
            }
        }
        return all;
    }

    private static String toJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T fromJson(String s, TypeReference<T> type) {
        if (s == null) {
            return null;
        }
        try {
            return JSON.readValue(s, type);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
