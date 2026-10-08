package de.yawi.installer.core.elevation;

import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLineTest {

    @Test
    void fieldsSurviveTabsNewlinesAndBackslashes() {
        List<String> fields = List.of("output", "a\tb", "line1\nline2\r", "C:\\path\\x", "");
        String line = EventLine.encode(fields);
        assertTrue(line.indexOf('\n') < 0 && line.indexOf('\r') < 0, line);
        assertEquals(Optional.of(fields), EventLine.decode(line));
        assertEquals(Optional.empty(), EventLine.decode(""));
        assertEquals(Optional.of(List.of("x", "y")), EventLine.decode("x\ty"));
    }

    @Test
    void listsTravelAsOneField() {
        List<String> items = List.of("a", "b c", "");
        String field = EventLine.list(items);
        assertEquals(items, EventLine.unlist(field));
        assertEquals(List.of(), EventLine.unlist(""));
        List<String> decoded = EventLine.decode(EventLine.encode("rollbackFinished", "1", field)).orElseThrow();
        assertEquals(items, EventLine.unlist(decoded.get(2)));
    }

    @Test
    void everyRecordEntryRoundTrips() {
        List<InstallationRecord.Entry> entries = List.of(
                new InstallationRecord.CreatedFile("a/b.txt"),
                new InstallationRecord.CreatedDirectory("/abs/dir"),
                new InstallationRecord.ReplacedFile("x", ".installer/backup/1/x"),
                new InstallationRecord.ModeChanged("bin/run", "644"),
                new InstallationRecord.StepStarted("service"),
                new InstallationRecord.StepFinished("service", "FAILED", "exit 3\ttab"),
                new InstallationRecord.StepFinished("service", "DONE", null),
                new InstallationRecord.RunFinished(Instant.parse("2026-09-14T10:00:00Z")));
        for (InstallationRecord.Entry entry : entries) {
            List<String> fields = EventLine.decode(EventLine.encodeRecord(entry)).orElseThrow();
            assertEquals(Optional.of(entry), EventLine.decodeRecord(fields), entry.toString());
        }
        assertEquals(Optional.empty(), EventLine.decodeRecord(List.of("record", "unknown", "x")));
        assertEquals(Optional.empty(), EventLine.decodeRecord(List.of("record", "replaced")));
    }
}
