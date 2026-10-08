package de.yawi.installer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The result file of the update contract: the four fields, strings escaped, parent folder created. */
class ResultFileTest {

    @TempDir
    Path tmp;

    @Test
    void jsonHasTheContractsFields() {
        String json = ResultFile.json(11, "1.2.3", Instant.parse("2026-09-13T18:00:00Z"), "no \"record\"\nhere\\");
        assertEquals("{\"exitCode\":11,\"version\":\"1.2.3\",\"finishedAt\":\"2026-09-13T18:00:00Z\","
                + "\"message\":\"no \\\"record\\\"\\nhere\\\\\"}", json);
        assertEquals("a\\u0001b", ResultFile.escape("a" + (char) 1 + "b"));
    }

    @Test
    void writeCreatesTheFolder() throws Exception {
        Path file = tmp.resolve("updates/1.2.3/result.json");
        ResultFile.write(file, 0, "1.2.3", Instant.now(), "ok");
        assertTrue(Files.readString(file).startsWith("{\"exitCode\":0,\"version\":\"1.2.3\""));
    }
}
