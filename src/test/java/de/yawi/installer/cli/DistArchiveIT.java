package de.yawi.installer.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * opt-in: checks the portable archive that {@code ./mvnw -Pdist package} wrote
 * ({@code -Dyawi.dist.archive=target/dist/yawi-installer-<v>-<os>-<arch>.tar.gz}). Skipped
 * without the property, so the normal build needs no jpackage. Unpacks with the system
 * {@code tar} (the same tool that wrote it; bsdtar on Windows reads the zip), then runs the
 * entry the launcher's update contract names, relative to the archive root - Linux
 * {@code bin/yawi-installer}, Windows {@code yawi-installer.exe}, macOS
 * {@code yawi-installer.app/Contents/MacOS/yawi-installer} -
 * without a display, and shows that an {@code installer.xml} next to that entry wins over
 * the bundled one.
 */
class DistArchiveIT {

    private static final String MANIFEST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <installer schemaVersion="1">
              <product id="dist-check" version="9.9.9"><name>Dist Check</name></product>
              <languages default="en"><language code="en"/></languages>
              <wizard><page name="welcome"/><page name="destination"/><page name="progress"/><page name="finish"/></wizard>
              <destination><default os="linux">$HOME/dist-check</default><default os="windows">%LOCALAPPDATA%\\dist-check</default><default os="macos">$HOME/dist-check</default></destination>
              <components><component id="core" required="true"><name>Core</name><step ref="dir"/></component></components>
              <steps><step id="dir" type="mkdir"><to>${destination}/data</to></step></steps>
            </installer>
            """;

    @TempDir
    static Path tmp;

    static Path entry;

    @BeforeAll
    static void unpack() throws Exception {
        String archive = System.getProperty("yawi.dist.archive");
        assumeTrue(archive != null && !archive.isBlank(), "set -Dyawi.dist.archive to run");
        Path file = Path.of(archive).toAbsolutePath();
        assertTrue(Files.isRegularFile(file), "archive not found: " + file);

        Process tar = new ProcessBuilder("tar", "-C", tmp.toString(), "-xf", file.toString())
                .redirectErrorStream(true).start();
        String out = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(tar.waitFor(120, TimeUnit.SECONDS), "tar did not end");
        assertEquals(0, tar.exitValue(), out);

        entry = tmp.resolve(entryPath());
        assertTrue(Files.isRegularFile(entry), "entry missing: " + entry);
        assertTrue(Files.isExecutable(entry), "entry not executable: " + entry);
    }

    @Test
    void helpAndVersionRunWithoutADisplay() throws Exception {
        Run help = run("--silent", "--help");
        assertEquals(0, help.exit(), help.err());
        assertTrue(help.out().contains("--update"), help.out());
        assertTrue(help.out().contains("--wait-pid"), help.out());

        Run version = run("--silent", "--version");
        assertEquals(0, version.exit(), version.err());
        assertTrue(version.out().startsWith("yawi-installer your-product "), version.out());
    }

    @Test
    void unknownOptionIsExit2WithoutAStackTrace() throws Exception {
        Run run = run("--silent", "--frobnicate");
        assertEquals(2, run.exit(), run.err());
        assertTrue(run.err().contains("--help"), run.err());
        assertFalse(run.err().contains("Exception"), run.err());
    }

    @Test
    void manifestNextToTheEntryWinsOverTheBundledOne() throws Exception {
        Path manifest = entry.resolveSibling("installer.xml");
        Files.writeString(manifest, MANIFEST);
        try {
            Run version = run("--silent", "--version");
            assertEquals(0, version.exit(), version.err());
            assertTrue(version.out().startsWith("yawi-installer dist-check 9.9.9"), version.out());

            Path dest = tmp.resolve("dest");
            Run dry = run("--silent", "--dry-run", "--dest=" + dest, "--accept-license");
            assertEquals(0, dry.exit(), dry.err());
            assertFalse(Files.exists(dest.resolve("data")), "dry run must not write");
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    record Run(int exit, String out, String err) {
    }

    private static Run run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(entry.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("DISPLAY");
        builder.environment().remove("WAYLAND_DISPLAY");
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "installer did not end");
        return new Run(process.exitValue(), out, err);
    }

    /** The entry of the launcher's update contract (su-node {@code release.DefaultEntries}). */
    private static String entryPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "yawi-installer.exe";
        }
        if (os.contains("mac")) {
            return "yawi-installer.app/Contents/MacOS/yawi-installer";
        }
        return "bin/yawi-installer";
    }
}
