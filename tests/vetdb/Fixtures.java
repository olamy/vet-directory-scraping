package vetdb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads the saved pages under tests/fixtures, whether tests run from the project root or from tests/. */
public final class Fixtures {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    public static String read(String relative) {
        for (Path base : new Path[] {Path.of("tests", "fixtures"), Path.of("fixtures")}) {
            Path p = base.resolve(relative);
            if (Files.exists(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        throw new IllegalArgumentException("fixture not found: " + relative + " (run from the project root)");
    }

    public static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
