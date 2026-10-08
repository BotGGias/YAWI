package de.yawi.installer.core.state;

import de.yawi.installer.core.state.InstallationRecord.CreatedDirectory;
import de.yawi.installer.core.state.InstallationRecord.CreatedFile;
import de.yawi.installer.core.state.InstallationRecord.ModeChanged;
import de.yawi.installer.core.state.InstallationRecord.ReplacedFile;
import de.yawi.installer.core.state.InstallationRecord.StepFinished;
import de.yawi.installer.core.state.InstallationRecord.StepStarted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E14-S01: the record is written as it grows and can be read back, even after a crash. */
class InstallationRecordTest {

    @TempDir
    Path tmp;

    private InstallationRecord record(Path destination) {
        return new InstallationRecord("su", "2.4.1", Instant.parse("2026-09-13T18:00:00Z"), destination,
                List.of("core", "server"), Map.of("serverPort", "50000"));
    }

    @Test
    void roundTripThroughTheFile() throws IOException {
        Path destination = tmp.resolve("dest");
        InstallationRecord record = record(destination);
        Path file = RecordWriter.defaultFile(destination);
        try (RecordWriter writer = RecordWriter.open(file, record)) {
            record.stepStarted("unpack");
            record.directoryCreated(destination.resolve("bin"));
            record.fileCreated(destination.resolve("bin/su & \"co\" <x>"));
            record.fileCreated(tmp.resolve("outside.tmp"));
            record.fileReplaced(destination.resolve("bin/old"), destination.resolve(".installer/backup/1/bin/old"));
            record.modeChanged(destination.resolve("bin/old"), "644");
            record.stepFinished("unpack", "DONE", null);
            record.stepFinished("run", "FAILED", "exit code 3\nsecond line");
        }

        assertTrue(Files.readString(file).endsWith("</record>\n"));
        InstallationRecord read = RecordReader.read(file);
        assertEquals("su", read.productId());
        assertEquals("2.4.1", read.productVersion());
        assertEquals(record.startedAt(), read.startedAt());
        assertEquals(destination.toAbsolutePath(), read.destination());
        assertEquals(List.of("core", "server"), read.components());
        assertEquals(Map.of("serverPort", "50000"), read.inputs());
        // The record's own directory and file come first.
        assertEquals(List.of(
                new CreatedDirectory(".installer"),
                new CreatedFile(".installer/record.xml"),
                new StepStarted("unpack"),
                new CreatedDirectory("bin"),
                new CreatedFile("bin/su & \"co\" <x>"),
                new CreatedFile(tmp.resolve("outside.tmp").toAbsolutePath().toString()),
                new ReplacedFile("bin/old", ".installer/backup/1/bin/old"),
                new ModeChanged("bin/old", "644"),
                new StepFinished("unpack", "DONE", null),
                new StepFinished("run", "FAILED", "exit code 3 second line")), read.entries());
        assertEquals(destination.toAbsolutePath().resolve("bin"), read.resolve("bin"));
        assertTrue(read.tracks(destination.resolve("bin/old")));
    }

    @Test
    void tracksCreatedAndReplacedPaths() {
        Path destination = tmp.resolve("dest");
        InstallationRecord record = record(destination);
        record.fileCreated(destination.resolve("a"));
        record.fileReplaced(destination.resolve("b"), destination.resolve(".installer/backup/1/b"));
        record.directoryCreated(destination.resolve("dir"));
        record.modeChanged(destination.resolve("c"), "755");
        assertTrue(record.tracks(destination.resolve("a")));
        assertTrue(record.tracks(destination.resolve("./b")));
        assertFalse(record.tracks(destination.resolve("dir")));
        assertFalse(record.tracks(destination.resolve("c")));
    }

    @Test
    void entriesAreOnDiskBeforeClose() throws IOException {
        Path destination = tmp.resolve("dest");
        InstallationRecord record = record(destination);
        Path file = RecordWriter.defaultFile(destination);
        RecordWriter writer = RecordWriter.open(file, record);
        record.fileCreated(destination.resolve("a.txt"));
        record.stepStarted("s1");

        // Simulated crash: never closed. The reader repairs the missing closing tag.
        String text = Files.readString(file);
        assertTrue(text.contains("<file path=\"a.txt\"/>"), text);
        assertFalse(text.contains("</record>"));
        InstallationRecord read = RecordReader.read(file);
        assertEquals(List.of(
                new CreatedDirectory(".installer"), new CreatedFile(".installer/record.xml"),
                new CreatedFile("a.txt"), new StepStarted("s1")), read.entries());
        writer.close();
    }

    @Test
    void halfWrittenLastLineIsDropped() throws IOException {
        Path file = tmp.resolve("record.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <record version="1" product="p" productVersion="1" started="2026-01-01T00:00:00Z" destination="/d">
                  <file path="ok.txt"/>
                  <file path="half""", StandardCharsets.UTF_8);
        InstallationRecord read = RecordReader.read(file);
        assertEquals(List.of(new CreatedFile("ok.txt")), read.entries());
    }

    @Test
    void secretsAreLeftOut() {
        Map<String, String> safe = InstallationRecord.withoutSecrets(
                Map.of("serverPort", "1", "adminPassword", "x", "apiKey", "y", "mode", "lan"),
                Map.of("apiKey", "Access token"));
        assertEquals(Map.of("serverPort", "1", "mode", "lan"), safe);
    }

    @Test
    void wrongRootOrVersionIsRejected() throws IOException {
        Path file = tmp.resolve("other.xml");
        Files.writeString(file, "<installer/>");
        assertThrows(IOException.class, () -> RecordReader.read(file));
        Files.writeString(file, "<record version=\"99\" product=\"p\" productVersion=\"1\" started=\"2026-01-01T00:00:00Z\" destination=\"/d\"/>");
        assertThrows(IOException.class, () -> RecordReader.read(file));
    }
}
