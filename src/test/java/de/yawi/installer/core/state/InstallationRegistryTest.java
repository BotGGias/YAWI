package de.yawi.installer.core.state;

import de.yawi.installer.core.platform.Architecture;
import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.LinuxPlatform;
import de.yawi.installer.core.platform.MacPlatform;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.WindowsPlatform;
import de.yawi.installer.core.state.ExistingInstallation.Status;
import de.yawi.installer.core.state.InstallationRegistry.Registration;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E14-S01-T03 / E14-S02-T01: the register remembers installations by destination and survives damage. */
class InstallationRegistryTest {

    private static final String PRODUCT = "su";

    @TempDir
    Path tmp;

    private InstallationRegistry registry() {
        return InstallationRegistry.at(tmp.resolve("registry"), PRODUCT);
    }

    private static InstallationRecord record(Path destination, String version) {
        return new InstallationRecord(PRODUCT, version, Instant.parse("2026-09-14T10:00:00Z"), destination,
                List.of("core"), Map.of("port", "5000"));
    }

    @Test
    void directoryIsInstallerBelowTheConfigDirNeverTheConfigDirItself() {
        Platform linux = new LinuxPlatform(Architecture.X64, Environment.of(Map.of("HOME", "/home/me"),
                Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp")));
        Platform windows = new WindowsPlatform(Architecture.X64, Environment.of(
                Map.of("APPDATA", "C:\\Users\\me\\AppData\\Roaming", "USERPROFILE", "C:\\Users\\me"),
                Map.of("user.home", "C:\\Users\\me", "java.io.tmpdir", "C:\\Temp")));
        Platform mac = new MacPlatform(Architecture.AARCH64, Environment.of(Map.of("HOME", "/Users/me"),
                Map.of("user.home", "/Users/me", "java.io.tmpdir", "/tmp")));
        for (Platform platform : List.of(linux, windows, mac)) {
            Path expected = platform.configDir("your-product").resolve("installer");
            assertEquals(expected, InstallationRegistry.forPlatform(platform, "your-product").directory(),
                    platform.os().toString());
        }
        assertEquals(Path.of("/home/me/.config/your-product/installer"),
                InstallationRegistry.forPlatform(linux, "your-product").directory());
    }

    @Test
    void registerListAndUnregister() throws IOException {
        InstallationRegistry registry = registry();
        assertTrue(registry.list().isEmpty(), "no directory yet = empty register");

        Path dest = tmp.resolve("app");
        registry.register(record(dest, "1.0.0"));

        List<Registration> listed = registry.list();
        assertEquals(1, listed.size());
        Registration entry = listed.get(0);
        assertEquals(PRODUCT, entry.productId());
        assertEquals("1.0.0", entry.productVersion());
        assertEquals(dest.toAbsolutePath().normalize(), entry.destination());
        assertEquals(registry.fileFor(dest), entry.file());
        assertTrue(Files.isRegularFile(entry.file()));
        assertTrue(Files.readString(entry.file()).contains("<installation version=\"1\""));
        assertFalse(Files.exists(entry.file().resolveSibling(entry.file().getFileName() + ".tmp")), "temp file moved");

        assertTrue(registry.unregister(entry));
        assertFalse(registry.unregister(entry), "already gone");
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void reRegisteringTheSameDestinationReplacesTheEntryAndListsItFirst() throws Exception {
        InstallationRegistry registry = registry();
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        registry.register(record(a, "1.0.0"));
        Thread.sleep(5);
        registry.register(record(b, "1.0.0"));
        Thread.sleep(5);
        registry.register(record(a.resolve("x").resolve(".."), "1.0.1")); // same folder, spelled differently

        List<Registration> listed = registry.list();
        assertEquals(2, listed.size(), "one entry per destination");
        assertEquals(a.toAbsolutePath().normalize(), listed.get(0).destination(), "most recently registered first");
        assertEquals("1.0.1", listed.get(0).productVersion());
        assertEquals(b.toAbsolutePath().normalize(), listed.get(1).destination());
    }

    @Test
    void keyIsStableForTheSameFolderAndDiffersBetweenFolders() {
        Path dest = tmp.resolve("dest");
        assertEquals(InstallationRegistry.key(dest), InstallationRegistry.key(dest.resolve(".").resolve("sub").resolve("..")));
        assertNotEquals(InstallationRegistry.key(dest), InstallationRegistry.key(tmp.resolve("other")));
        assertEquals(16, InstallationRegistry.key(dest).length());
    }

    @Test
    void damagedAndForeignEntriesAreSkipped() throws IOException {
        InstallationRegistry registry = registry();
        registry.register(record(tmp.resolve("good"), "1.0.0"));
        Path dir = registry.directory().resolve(InstallationRegistry.INSTALLATIONS);
        Files.writeString(dir.resolve("garbage.xml"), "<installation version=\"1\" product=\"su\"", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("noversion.xml"), "<installation product=\"su\" destination=\"/x\" registeredAt=\"2026-09-14T10:00:00Z\"/>");
        Files.writeString(dir.resolve("future.xml"), "<installation version=\"99\" product=\"su\" productVersion=\"1\" destination=\"/x\" registeredAt=\"2026-09-14T10:00:00Z\"/>");
        Files.writeString(dir.resolve("other.xml"), "<installation version=\"1\" product=\"someone-else\" productVersion=\"1\" destination=\"/x\" registeredAt=\"2026-09-14T10:00:00Z\"/>");
        Files.writeString(dir.resolve("leftover.xml.tmp"), "half");
        Files.writeString(dir.resolve("notes.txt"), "not xml");

        List<Registration> listed = registry.list();
        assertEquals(1, listed.size());
        assertEquals(tmp.resolve("good").toAbsolutePath().normalize(), listed.get(0).destination());
    }

    @Test
    void installationsAreCheckedAgainstTheDisk() throws IOException {
        InstallationRegistry registry = registry();

        Path intact = tmp.resolve("intact");
        InstallationRecord record = record(intact, "1.0.0");
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(intact), record)) {
            record.stepStarted("x");
        }
        registry.register(record);

        Path gone = tmp.resolve("gone");
        registry.register(record(gone, "1.0.0"));

        Path noRecord = tmp.resolve("no-record");
        Files.createDirectories(noRecord);
        registry.register(record(noRecord, "1.0.0"));

        Path unreadable = tmp.resolve("unreadable");
        Files.createDirectories(RecordWriter.defaultFile(unreadable).getParent());
        Files.writeString(RecordWriter.defaultFile(unreadable), "<record version=\"1\" product=\"su\"");
        registry.register(record(unreadable, "1.0.0"));

        Path foreign = tmp.resolve("foreign");
        InstallationRecord other = new InstallationRecord("other", "2", Instant.EPOCH, foreign, List.of(), Map.of());
        RecordWriter.open(RecordWriter.defaultFile(foreign), other).close();
        registry.register(record(foreign, "1.0.0"));

        Map<Path, ExistingInstallation> byDestination = new java.util.HashMap<>();
        for (ExistingInstallation e : registry.installations()) {
            byDestination.put(e.destination().getFileName(), e);
        }
        assertEquals(5, byDestination.size());

        ExistingInstallation ok = byDestination.get(Path.of("intact"));
        assertEquals(Status.INTACT, ok.status());
        assertTrue(ok.isIntact());
        assertEquals("1.0.0", ok.installedVersion());
        assertEquals(List.of("core"), ok.record().orElseThrow().components());

        assertEquals(Status.DESTINATION_MISSING, byDestination.get(Path.of("gone")).status());
        assertEquals(Status.RECORD_MISSING, byDestination.get(Path.of("no-record")).status());
        assertEquals(Status.RECORD_UNREADABLE, byDestination.get(Path.of("unreadable")).status());
        assertEquals(Status.FOREIGN_RECORD, byDestination.get(Path.of("foreign")).status());
        for (String name : List.of("gone", "no-record", "unreadable", "foreign")) {
            ExistingInstallation orphan = byDestination.get(Path.of(name));
            assertTrue(orphan.isOrphaned(), name);
            assertTrue(orphan.record().isEmpty(), name);
            assertEquals("1.0.0", orphan.installedVersion(), name + ": falls back to the registered version");
            assertFalse(orphan.detail().isBlank(), name);
        }
    }

    @Test
    void installedVersionComesFromTheRecordWhenItIsNewerThanTheEntry() throws IOException {
        InstallationRegistry registry = registry();
        Path dest = tmp.resolve("dest");
        registry.register(record(dest, "1.0.0"));
        // Someone updated without registering (an installer from before E14-S02): the disk wins.
        RecordWriter.open(RecordWriter.defaultFile(dest), record(dest, "1.0.5")).close();
        assertEquals("1.0.5", registry.installations().get(0).installedVersion());
    }
}
