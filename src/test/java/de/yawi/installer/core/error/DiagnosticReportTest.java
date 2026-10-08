package de.yawi.installer.core.error;

import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E17-S03-T02/T04: the report has every section and masks what it must. */
class DiagnosticReportTest {

    private final Platform platform = PlatformFactory.current();

    @TempDir
    Path tmp;

    private Path log(String... lines) throws Exception {
        Path log = tmp.resolve("installer.log");
        Files.write(log, java.util.List.of(lines), StandardCharsets.UTF_8);
        return log;
    }

    @Test
    void fullReportHasEverySection() throws Exception {
        String home = System.getProperty("user.home");
        Path record = tmp.resolve("record.xml");
        Files.writeString(record, "<record version=\"1\"/>");
        Path log = log("12:00:00.000 INFO  x - installing to " + home + "/apps/su", "12:00:01.000 ERROR y - boom");
        InstallerException failure = new PermissionDeniedException(Path.of(home, "apps", "su"),
                new java.nio.file.AccessDeniedException(home + "/apps/su"));

        String report = DiagnosticReport.build(new DiagnosticReport.Context(
                Optional.of(TestManifests.bundled()), platform, Optional.of(tmp), Set.of("core", "server"),
                Map.of("serverPort", "50000"), Optional.of(record), Optional.of(failure), log));

        for (String section : new String[] {"YAWI diagnostic report", "Failure", "System", "Selection",
                "Manifest", "Installation record", "Log ("}) {
            assertTrue(report.contains("=== " + section), "section " + section + " in:\n" + report);
        }
        assertTrue(report.contains("Product: your-product"), report);
        assertTrue(report.contains("Code: PERMISSION_DENIED (exit 5)"), report);
        assertTrue(report.contains("Caused by: java.nio.file.AccessDeniedException"), report);
        assertTrue(report.contains("Components: core, server"), report);
        assertTrue(report.contains("Input serverPort = 50000"), report);
        assertTrue(report.contains("Origin: CLASSPATH (/installer.xml)"), report);
        assertTrue(report.contains("<installer schemaVersion=\"1\""), "manifest text included: " + report);
        assertTrue(report.contains("<record version=\"1\"/>"), report);
        assertTrue(report.contains("ERROR y - boom"), report);
        assertTrue(report.contains("Free space at destination: "), report);
        // Masking: the home path never appears, neither in the failure nor in the log.
        assertFalse(report.contains(home), report);
        assertTrue(report.contains("~/apps/su"), report);
        assertFalse(report.contains("\tat "), "no stack traces");
    }

    @Test
    void userNameIsMaskedAsAWordButNotInsideOtherWords() {
        String user = System.getProperty("user.name");
        assumeTrue(user != null && user.length() > 1);
        String masked = DiagnosticReport.mask("user=" + user + " path /srv/" + user + "/x and " + user + "name123");
        assertTrue(masked.contains("user=[user] path /srv/[user]/x"), masked);
        assertTrue(masked.contains(user + "name123"), "part of a longer word stays: " + masked);
    }

    @Test
    void credentialsInUrlsAreBlanked() {
        String masked = DiagnosticReport.mask("GET https://h/x?file=a.zip&token=abc123&sig=xyz&Api_Key=k1 done "
                + "and https://h/y?password=pw#frag");
        assertEquals("GET https://h/x?file=a.zip&token=[masked]&sig=[masked]&Api_Key=[masked] done "
                + "and https://h/y?password=[masked]#frag", masked);
    }

    @Test
    void logIsCutToTheLastLines() throws Exception {
        Path log = log(IntStream.rangeClosed(1, DiagnosticReport.MAX_LOG_LINES + 50)
                .mapToObj(i -> "line " + i).toArray(String[]::new));
        String tail = DiagnosticReport.logTail(log);
        assertTrue(tail.startsWith("[... 50 earlier lines omitted ...]\nline 51\n"), tail.substring(0, 80));
        assertTrue(tail.endsWith("line " + (DiagnosticReport.MAX_LOG_LINES + 50) + "\n"));
    }

    @Test
    void missingPiecesAreNotedNotFatal() {
        String report = DiagnosticReport.build(new DiagnosticReport.Context(Optional.empty(), platform,
                Optional.empty(), Set.of(), Map.of(), Optional.empty(), Optional.empty(), tmp.resolve("nope.log")));
        assertTrue(report.contains("=== Failure ===\n(none)"), report);
        assertTrue(report.contains("=== Manifest ===\n(not loaded)"), report);
        assertTrue(report.contains("=== Installation record ===\n(none)"), report);
        assertTrue(report.contains("(no log file at "), report);
        assertTrue(report.contains("Components: (none)"), report);
    }

    @Test
    void saveWritesUtf8AndCreatesParents() throws Exception {
        Path file = DiagnosticReport.save("Grüße\n", tmp.resolve("deep/report.txt"));
        assertEquals("Grüße\n", Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(DiagnosticReport.defaultFileName().matches("yawi-installer-report-\\d{8}-\\d{6}\\.txt"));
    }
}
