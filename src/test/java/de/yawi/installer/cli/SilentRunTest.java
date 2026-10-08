package de.yawi.installer.cli;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.LocalCache;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.download.TestHttpServerAccess;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.integrity.ChecksumVerifier;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * with the update contract, end to end and headless: install
 * a launcher-like product, then update it the way su-node calls the
 * installer (cache, wait-pid, result file, log file, relaunch).
 */
class SilentRunTest {

    private static final String MANIFEST = "/manifest/cli/launcher-update.xml";
    private static final String SCRIPT = "#!/bin/sh\ntouch \"$(dirname \"$0\")/relaunched\"\n";

    private static TestHttpServerAccess server;
    private static byte[] archive;
    private static String sha256;
    private static final boolean UNIX = PlatformFactory.current().os() != OperatingSystem.WINDOWS;

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    @BeforeAll
    static void startServer() throws Exception {
        archive = zip();
        sha256 = ChecksumVerifier.hex(MessageDigest.getInstance("SHA-256").digest(archive));
        server = new TestHttpServerAccess();
        server.customBytes(archive);
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void restoreLogging() {
        // --log moves the global log; other tests expect the default location and a talkative console.
        LogSetup.setConsoleLevel(null);
        LogSetup.redirect(Path.of(System.getProperty("java.io.tmpdir"), "yawi-installer"));
        System.clearProperty("yawi.waitTimeoutSeconds");
        System.clearProperty("yawi.elevation");
    }

    /** The elevated block runs in a second process; {@code -Dyawi.elevation=direct} spares the prompt. */
    @Test
    void elevatedStepsRunThroughTheChildProcessAndNoneMeansExit13() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                de.yawi.installer.core.platform.PlatformFactory.current().isElevated());
        InstallManifest manifest = TestManifests.parse("/manifest/engine/elevated.xml");
        Path dest = tmp.resolve("elevated");
        System.setProperty("yawi.elevation", "direct");
        int code = run(manifest, "--silent", "--dest=" + dest, "--input.adminPassword=hunter2", "--output=json");
        assertEquals(0, code, stderr.toString());
        assertEquals("hunter2", Files.readString(dest.resolve("service.txt")).strip());
        String out = stdout.toString();
        assertTrue(out.contains("\"event\":\"stepStarted\",\"step\":\"service\",\"index\":2,\"count\":3"), out);
        assertTrue(Files.isRegularFile(registry("elev").fileFor(dest)), "registered by the parent");

        System.setProperty("yawi.elevation", "none");
        Path other = tmp.resolve("elevated-none");
        code = run(manifest, "--silent", "--dest=" + other, "--input.adminPassword=x");
        assertEquals(13, code, stderr.toString());
        assertFalse(Files.exists(other));
        assertTrue(stderr.toString().contains("administrator"), stderr.toString());
    }

    @Test
    void installsThenUpdatesFromTheCacheLikeTheLauncherDoes() throws Exception {
        Path dest = tmp.resolve("dist");

        // 1. Initial installation, as a packager's silent roll-out would do it.
        int code = run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--lang=de",
                "--input.port=5000");
        assertEquals(0, code, stderr.toString());
        assertTrue(Files.isRegularFile(dest.resolve("README.txt")));
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals("1.0.0", record.productVersion());
        assertEquals("5000", record.inputs().get("port"));
        List<InstallationRegistry.Registration> registered = registry("test").list();
        assertEquals(1, registered.size(), "the installation is registered");
        assertEquals("1.0.0", registered.get(0).productVersion());
        assertEquals(dest.toAbsolutePath().normalize(), registered.get(0).destination());
        assertTrue(stdout.toString().contains("Installiere Test 1.0.0"), stdout.toString());
        assertTrue(stdout.toString().contains("Version 1.0.0 installiert."), stdout.toString());

        // 2. The update call: cache holds the archive under its checksum, the server now serves garbage.
        Path cache = tmp.resolve("updates/1.0.1");
        Files.createDirectories(cache.resolve("launcher"));
        Files.write(cache.resolve("launcher/su-test-launcher-1.0.1.zip"), archive);
        Files.writeString(cache.resolve(LocalCache.INDEX_FILE), sha256 + "  launcher/su-test-launcher-1.0.1.zip\n");
        server.custom("not the archive");
        Path result = cache.resolve("result.json");
        Path log = tmp.resolve("logs/installer-1.log");
        try {
            code = run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--cache=" + cache,
                    "--relaunch", "--result=" + result, "--log=" + log, "--lang=en", "--accept-license");
        } finally {
            server.customBytes(archive);
        }
        assertEquals(0, code, stderr.toString());
        String json = Files.readString(result);
        assertTrue(json.startsWith("{\"exitCode\":0,\"version\":\"1.0.1\",\"finishedAt\":\""), json);
        record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals("1.0.1", record.productVersion());
        assertEquals("5000", record.inputs().get("port"), "inputs come from the previous record");
        assertEquals(java.util.List.of("core"), record.components());
        registered = registry("su-test-launcher").list();
        assertEquals(1, registered.size(), "the update replaces the entry instead of adding one");
        assertEquals("1.0.1", registered.get(0).productVersion());
        assertTrue(Files.exists(cache.resolve("launcher/su-test-launcher-1.0.1.zip")), "cache untouched");
        String logText = Files.readString(log);
        assertTrue(logText.contains("Updating su-test-launcher 1.0.0 -> 1.0.1"), logText);
        assertTrue(logText.contains("Result written to"), logText);
        assertTrue(stdout.toString().contains("Updating Test Launcher 1.0.0 to 1.0.1"), stdout.toString());
        if (UNIX) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (!Files.exists(dest.resolve("relaunched")) && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(dest.resolve("relaunched")), "--relaunch started " + dest.resolve("testpayload"));
        }
    }

    @Test
    void failedUpdateRollsBackKeepsTheOldRecordAndStillRelaunches() throws Exception {
        Path dest = tmp.resolve("dist-rb");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--lang=en"),
                stderr.toString());
        Files.writeString(dest.resolve("README.txt"), "edited by the user\n");
        String oldRecord = Files.readString(RecordWriter.defaultFile(dest));

        // The update's manifest adds a step that fails after the archive has been unpacked over the installation.
        Path cache = tmp.resolve("updates/1.0.2");
        Files.createDirectories(cache.resolve("launcher"));
        Files.write(cache.resolve("launcher/su-test-launcher-1.0.2.zip"), archive);
        Files.writeString(cache.resolve(LocalCache.INDEX_FILE), sha256 + "  launcher/su-test-launcher-1.0.2.zip\n");
        Path result = cache.resolve("result.json");
        Path log = tmp.resolve("logs/installer-rb.log");
        int code = run(failingManifest("1.0.2"), "--silent", "--update", "--dest=" + dest, "--cache=" + cache,
                "--relaunch", "--result=" + result, "--log=" + log, "--lang=en", "--accept-license");

        assertEquals(9, code, "the step failure's exit code, not a rollback code: " + stderr);
        assertEquals("edited by the user\n", Files.readString(dest.resolve("README.txt")), "replaced file restored");
        assertEquals(oldRecord, Files.readString(RecordWriter.defaultFile(dest)), "previous record restored");
        assertFalse(Files.exists(dest.resolve(".installer/backup")));
        assertEquals("1.0.0", registry("su-test-launcher").list().get(0).productVersion(),
                "a rolled-back update leaves the register at the installed version");
        String json = Files.readString(result);
        assertTrue(json.startsWith("{\"exitCode\":9,\"version\":\"1.0.2\""), json);
        assertTrue(json.contains("changes undone"), json);
        assertTrue(stdout.toString().contains("Changes undone"), stdout.toString());
        assertTrue(stdout.toString().contains("Cannot be undone automatically: boom"), stdout.toString());
        String logText = Files.readString(log);
        assertTrue(logText.contains("Rollback finished"), logText);
        if (UNIX) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (!Files.exists(dest.resolve("relaunched")) && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(dest.resolve("relaunched")), "--relaunch starts the restored launcher");
        }

        // JSON output names the rollback events.
        Files.writeString(dest.resolve("README.txt"), "edited again\n");
        code = run(failingManifest("1.0.2"), "--silent", "--update", "--dest=" + dest, "--cache=" + cache,
                "--output=json", "--lang=en", "--accept-license");
        assertEquals(9, code);
        assertTrue(stdout.toString().contains("{\"event\":\"rollbackStarted\""), stdout.toString());
        assertTrue(stdout.toString().contains("{\"event\":\"rollbackFinished\",\"clean\":true"), stdout.toString());
        assertEquals("edited again\n", Files.readString(dest.resolve("README.txt")));
    }

    @Test
    void updateWithoutARecordIsExit11AndStillWritesTheResult() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("nothing-here"));
        Path result = tmp.resolve("result.json");
        int code = run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--result=" + result,
                "--accept-license", "--lang=en");
        assertEquals(11, code);
        assertTrue(Files.readString(result).startsWith("{\"exitCode\":11,\"version\":\"1.0.1\""));
        assertTrue(stderr.toString().contains("There is no installation in " + dest), stderr.toString());
        assertFalse(Files.exists(RecordWriter.defaultFile(dest)), "nothing written");
    }

    @Test
    void updateOfAnotherProductsFolderIsExit11() throws Exception {
        Path dest = tmp.resolve("other");
        assertEquals(0, run(TestManifests.bundled(), "--silent", "--dest=" + dest, "--accept-license",
                "--components=core"), stderr.toString());
        assertEquals(11, run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--accept-license"));
    }

    @Test
    void tamperedCacheEntryIsExit4() throws Exception {
        Path dest = tmp.resolve("dist");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license"), stderr.toString());
        Path cache = tmp.resolve("cache");
        Files.createDirectories(cache.resolve("launcher"));
        Files.writeString(cache.resolve("launcher/x.zip"), "tampered");
        Files.writeString(cache.resolve(LocalCache.INDEX_FILE), sha256 + "  launcher/x.zip\n");
        int code = run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--cache=" + cache,
                "--accept-license");
        assertEquals(4, code, stderr.toString());
        assertEquals("1.0.0", RecordReader.read(RecordWriter.defaultFile(dest)).productVersion(), "untouched");
    }

    @Test
    void waitPidWaitsForTheProcessAndGivesUpAfterTheGracePeriod() throws Exception {
        assumeTrue(UNIX, "uses sleep(1)");
        Path dest = tmp.resolve("dist");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license"), stderr.toString());

        Process quick = new ProcessBuilder("sleep", "1").start();
        Instant start = Instant.now();
        int code = run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--accept-license",
                "--wait-pid=" + quick.pid(), "--wait-pid=999999999");
        assertEquals(0, code, stderr.toString());
        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofMillis(800)) > 0, "waited for sleep");

        Process slow = new ProcessBuilder("sleep", "600").start();
        try {
            System.setProperty("yawi.waitTimeoutSeconds", "1");
            Path result = tmp.resolve("result.json");
            code = run(manifest("1.0.1"), "--silent", "--update", "--dest=" + dest, "--accept-license",
                    "--wait-pid=" + slow.pid(), "--result=" + result, "--lang=en");
            assertEquals(12, code, stderr.toString());
            assertTrue(Files.readString(result).startsWith("{\"exitCode\":12"));
            assertTrue(stderr.toString().contains("did not exit within 1 seconds (process " + slow.pid()), stderr.toString());
        } finally {
            slow.destroyForcibly();
        }
    }

    @Test
    void dryRunShowsThePlanAndWritesNothing() throws Exception {
        Path dest = tmp.resolve("dry");
        int code = run(manifest("1.0.0"), "--silent", "--dry-run", "--dest=" + dest, "--accept-license",
                "--components=core,extra");
        assertEquals(0, code, stderr.toString());
        assertFalse(Files.exists(dest));
        assertTrue(registry("su-test-launcher").list().isEmpty(), "a dry run registers nothing");
        String out = stdout.toString();
        assertTrue(out.contains("1. [extract] unpack"), out);
        assertTrue(out.contains("3. [mkdir] extra-dir"), out);
    }

    @Test
    void licenseMustBeAcceptedAndBadInputsAreArgumentErrors() {
        Path dest = tmp.resolve("dist");
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--dest=" + dest));
        assertTrue(stderr.toString().contains("--accept-license"), stderr.toString());
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--input.port=80"));
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--components=nope"));
        assertFalse(Files.exists(dest));
    }

    @Test
    void helpVersionAndJsonOutput() throws Exception {
        assertEquals(0, run(manifest("1.0.0"), "--help"));
        String help = stdout.toString();
        assertTrue(help.contains("--update") && help.contains("--repair")
                && help.contains("--wait-pid=<pid>") && help.contains("12 "), help);
        assertTrue(help.contains("--uninstall") && help.contains("--purge"), help);
        assertTrue(help.contains("--config=<"), help); // <file> or <datei>, depending on the system language

        assertEquals(0, run(manifest("1.0.0"), "--version"));
        String[] versionLines = stdout.toString().strip().split("\\R");
        assertEquals("yawi-installer su-test-launcher 1.0.0", versionLines[0]);
        // The installer's own build version follows on a second line.
        assertTrue(versionLines[1].startsWith("installer build "), stdout.toString());

        Path dest = tmp.resolve("json");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--output=json"),
                stderr.toString());
        String[] lines = stdout.toString().strip().split("\\R");
        assertTrue(lines.length > 3, stdout.toString());
        for (String line : lines) {
            assertTrue(line.startsWith("{\"event\":\"") && line.endsWith("}"), line);
        }
        assertTrue(stdout.toString().contains("{\"event\":\"stepStarted\",\"step\":\"unpack\",\"index\":0,\"count\":3}"),
                stdout.toString());

        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + tmp.resolve("quiet"), "--accept-license",
                "--quiet"), stderr.toString());
        assertEquals("", stdout.toString());
    }

    // --- E14-S04 / E15-S04: repair ------------------------------------------------

    @Test
    void repairReinstallsTheRecordedSelectionWithoutAcceptLicense() throws Exception {
        Path dest = tmp.resolve("repair");
        // Install two components with a custom input; the record keeps both.
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--lang=en",
                "--components=core,extra", "--input.port=5000"), stderr.toString());
        assertTrue(Files.isRegularFile(dest.resolve("README.txt")));
        assertTrue(Files.isDirectory(dest.resolve("extra")));

        // A file goes missing; a repair puts it back. No --accept-license: the record implies the earlier acceptance.
        Files.delete(dest.resolve("README.txt"));
        Path result = tmp.resolve("repair-result.json");
        int code = run(manifest("1.0.0"), "--silent", "--repair", "--dest=" + dest, "--lang=en", "--result=" + result);
        assertEquals(0, code, stderr.toString());
        assertTrue(stdout.toString().contains("Repairing Test Launcher 1.0.0 in " + dest.toAbsolutePath().normalize()),
                stdout.toString());
        assertTrue(Files.isRegularFile(dest.resolve("README.txt")), "the missing file is restored");
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals("1.0.0", record.productVersion());
        assertEquals(2, record.components().size());
        assertTrue(record.components().containsAll(List.of("core", "extra")), "the recorded selection is kept");
        assertEquals("5000", record.inputs().get("port"));
        assertEquals(1, registry("su-test-launcher").list().size(), "a repair replaces the entry, it does not add one");
        assertTrue(Files.readString(result).startsWith("{\"exitCode\":0,\"version\":\"1.0.0\""));
    }

    @Test
    void repairWithoutARecordIsExit11AndChangesNothing() throws Exception {
        Path dest = tmp.resolve("no-record");
        assertEquals(11, run(manifest("1.0.0"), "--silent", "--repair", "--dest=" + dest, "--lang=en"));
        assertFalse(Files.exists(dest));
    }

    // --- E14-S03 / E15-S04: uninstall ---------------------------------------------

    @Test
    void uninstallRemovesTheInstallationAndKeepsWhatTheUserChangedOrAdded() throws Exception {
        Path dest = tmp.resolve("gone");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--lang=en",
                "--components=core,extra"), stderr.toString());
        assertTrue(Files.isDirectory(dest.resolve("extra")));
        // The user edits a shipped file and adds one of their own.
        Files.writeString(dest.resolve("README.txt"), "my notes\n");
        Files.setLastModifiedTime(dest.resolve("README.txt"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(60)));
        Files.writeString(dest.resolve("extra/saves.dat"), "user data\n");

        Path result = tmp.resolve("uninstall-result.json");
        int code = run(manifest("1.0.0"), "--silent", "--uninstall", "--dest=" + dest, "--lang=en",
                "--result=" + result, "--dry-run");
        assertEquals(0, code, stderr.toString());
        assertTrue(stdout.toString().contains("keep README.txt (changed since the installation)"), stdout.toString());
        assertTrue(stdout.toString().contains("keep extra/saves.dat (not installed by the installer)"), stdout.toString());
        assertTrue(Files.exists(dest.resolve("testpayload")), "a dry run changes nothing");
        assertEquals(1, registry("testpayload").list().size());

        code = run(manifest("1.0.0"), "--silent", "--uninstall", "--dest=" + dest, "--lang=en", "--result=" + result);
        assertEquals(0, code, stderr.toString());
        String out = stdout.toString();
        assertTrue(out.contains("Removing Test 1.0.0 from " + dest.toAbsolutePath().normalize()), out);
        assertTrue(out.contains("Kept (changed since the installation): README.txt"), out);
        assertTrue(out.contains("Kept (not installed by the installer): extra/saves.dat"), out);
        assertTrue(out.contains("Removed; 2 file(s) kept."), out);
        assertFalse(Files.exists(dest.resolve("testpayload")));
        assertFalse(Files.exists(dest.resolve(".installer")), "record and backups are gone");
        assertTrue(Files.exists(dest.resolve("README.txt")));
        assertTrue(Files.exists(dest.resolve("extra/saves.dat")));
        assertTrue(registry("su-test-launcher").list().isEmpty(), "unregistered");
        assertTrue(Files.readString(result).startsWith("{\"exitCode\":0,\"version\":\"1.0.0\""));

        // A second call finds no installation any more.
        assertEquals(11, run(manifest("1.0.0"), "--silent", "--uninstall", "--dest=" + dest, "--lang=en"));
        assertTrue(Files.exists(dest.resolve("README.txt")), "nothing touched");
    }

    @Test
    void purgeRemovesTheWholeFolderAndJsonOutputReportsIt() throws Exception {
        Path dest = tmp.resolve("purged");
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--dest=" + dest, "--accept-license", "--lang=en"),
                stderr.toString());
        Files.writeString(dest.resolve("notes.txt"), "user data\n");

        int code = run(manifest("1.0.0"), "--silent", "--uninstall", "--purge", "--dest=" + dest, "--output=json");
        assertEquals(0, code, stderr.toString());
        assertFalse(Files.exists(dest), "everything below the destination goes, and the folder with it");
        for (String line : stdout.toString().strip().split("\\R")) {
            assertTrue(line.startsWith("{\"event\":\"") && line.endsWith("}"), line);
        }
        assertTrue(stdout.toString().contains("{\"event\":\"uninstallFinished\",\"clean\":true,\"cancelled\":false,"),
                stdout.toString());
        assertTrue(stdout.toString().contains("\"destinationRemoved\":true}"), stdout.toString());
        assertTrue(registry("su-test-launcher").list().isEmpty());
    }

    // --- E15-S02: answer file ------------------------------------------------------

    @Test
    void answerFileAloneInstallsAndTheArgumentsOverrideIt() throws Exception {
        Path dest = tmp.resolve("dist-af");
        Path file = tmp.resolve("answers.xml");
        Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <answers version="1" product="su-test-launcher">
                  <language>de</language>
                  <destination>%s</destination>
                  <components><component id="core"/><component id="extra"/></components>
                  <sources><source id="launcher" kind="http"/></sources>
                  <inputs><input id="port" value="5005"/></inputs>
                  <license accepted="true"/>
                </answers>
                """.formatted(dest));

        // 1. Everything from the file: destination, selection, input, licence - and the language of the output.
        int code = run(manifest("1.0.0"), "--silent", "--config=" + file);
        assertEquals(0, code, stderr.toString());
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals(java.util.Set.of("core", "extra"), java.util.Set.copyOf(record.components()));
        assertEquals("5005", record.inputs().get("port"));
        assertTrue(Files.isDirectory(dest.resolve("extra")), "component 'extra' from the file was installed");
        assertTrue(stdout.toString().contains("Installiere Test Launcher 1.0.0"), stdout.toString());

        // 2. Arguments win: another folder, only the required component, another port, English.
        Path dest2 = tmp.resolve("dist-af2");
        code = run(manifest("1.0.0"), "--silent", "--config=" + file, "--dest=" + dest2, "--components=core",
                "--input.port=6000", "--lang=en");
        assertEquals(0, code, stderr.toString());
        record = RecordReader.read(RecordWriter.defaultFile(dest2));
        assertEquals(java.util.List.of("core"), record.components());
        assertEquals("6000", record.inputs().get("port"));
        assertFalse(Files.exists(dest2.resolve("extra")));
        assertTrue(stdout.toString().contains("Installing Test Launcher 1.0.0"), stdout.toString());
    }

    @Test
    void incompleteAnswerFileNamesTheMissingFieldAndChangesNothing() throws Exception {
        Path dest = tmp.resolve("dist-incomplete");

        // No licence line, no --accept-license: exit 2 naming the element, not a silent guess.
        Path noLicense = tmp.resolve("no-license.xml");
        Files.writeString(noLicense, "<answers version=\"1\"><destination>" + dest + "</destination></answers>");
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--config=" + noLicense));
        assertTrue(stderr.toString().contains("<license accepted=\"true\"/>"), stderr.toString());
        assertTrue(stderr.toString().contains("no-license.xml"), stderr.toString());
        // The argument fills the gap.
        assertEquals(0, run(manifest("1.0.0"), "--silent", "--config=" + noLicense, "--accept-license", "--dry-run"),
                stderr.toString());

        // A mandatory input without a default that the file does not give: named as <input id=...>.
        String xml = new String(TestManifests.bytes(MANIFEST), StandardCharsets.UTF_8)
                .replace("__VERSION__", "1.0.0").replace("__SHA256__", sha256).replace("__BASE__", server.base())
                .replace("default=\"4000\"", "required=\"true\"");
        InstallManifest strict = new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin(MANIFEST));
        Path noPort = tmp.resolve("no-port.xml");
        Files.writeString(noPort, "<answers version=\"1\"><destination>" + dest + "</destination><license accepted=\"true\"/></answers>");
        assertEquals(2, run(strict, "--silent", "--config=" + noPort));
        assertTrue(stderr.toString().contains("<input id=\"port\"> is missing"), stderr.toString());

        // A bad value in the file is the file's problem, too.
        Path badPort = tmp.resolve("bad-port.xml");
        Files.writeString(badPort, "<answers version=\"1\"><destination>" + dest + "</destination>"
                + "<inputs><input id=\"port\" value=\"80\"/></inputs><license accepted=\"true\"/></answers>");
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--config=" + badPort));
        assertTrue(stderr.toString().contains("<input id=\"port\" value=\"80\">"), stderr.toString());

        // Schema and manifest mismatches: exit 2 with the element named; a missing file likewise.
        Path unknown = tmp.resolve("unknown.xml");
        Files.writeString(unknown, "<answers version=\"1\"><components><component id=\"nope\"/></components>"
                + "<license accepted=\"true\"/></answers>");
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--config=" + unknown, "--dest=" + dest));
        assertTrue(stderr.toString().contains("<component id=\"nope\">"), stderr.toString());
        assertEquals(2, run(manifest("1.0.0"), "--silent", "--config=" + tmp.resolve("missing.xml"), "--dest=" + dest));
        assertTrue(stderr.toString().contains("missing.xml"), stderr.toString());

        assertFalse(Files.exists(dest));
    }

    // --- helpers -----------------------------------------------------------------

    /** The register of a product, in the scratch directory: a run must never touch the real one. */
    private InstallationRegistry registry(String productId) {
        return InstallationRegistry.at(tmp.resolve("registry"), productId);
    }

    private int run(InstallManifest manifest, String... args) {
        stdout.reset();
        stderr.reset();
        SilentRun run = new SilentRun(Arguments.parse(args), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), new SourceResolver(new HttpDownloader()),
                explicit -> manifest, id -> registry(id), () -> TestPlatforms.scratch(tmp.resolve("home")));
        return run.run();
    }

    /** The update manifest plus a run-command that fails after the archive is unpacked. */
    private static InstallManifest failingManifest(String version) {
        String xml = new String(TestManifests.bytes(MANIFEST), StandardCharsets.UTF_8)
                .replace("__VERSION__", version)
                .replace("__SHA256__", sha256)
                .replace("__BASE__", server.base())
                .replace("<step ref=\"exec\"/>", "<step ref=\"exec\"/><step ref=\"boom\"/>")
                .replace("<step id=\"extra-dir\"", "<step id=\"boom\" type=\"run-command\">"
                        + "<commands os=\"linux\"><command><arg>/bin/sh</arg><arg>-c</arg><arg>exit 2</arg></command></commands>"
                        + "<commands os=\"macos\"><command><arg>/bin/sh</arg><arg>-c</arg><arg>exit 2</arg></command></commands>"
                        + "<commands os=\"windows\"><command><arg>cmd</arg><arg>/c</arg><arg>exit 2</arg></command></commands>"
                        + "</step><step id=\"extra-dir\"");
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin(MANIFEST));
    }

    private static InstallManifest manifest(String version) {
        String xml = new String(TestManifests.bytes(MANIFEST), StandardCharsets.UTF_8)
                .replace("__VERSION__", version)
                .replace("__SHA256__", sha256)
                .replace("__BASE__", server.base());
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin(MANIFEST));
    }

    /** The "launcher archive": a script that leaves a marker next to itself, plus a README. */
    private static byte[] zip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("testpayload"));
            zip.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("README.txt"));
            zip.write("test launcher\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
