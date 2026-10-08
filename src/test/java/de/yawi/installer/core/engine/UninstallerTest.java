package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E14-S03: the record walked backwards removes the installation, and nothing the user made. */
class UninstallerTest {

    private static final boolean POSIX = PlatformFactory.current().os() != OperatingSystem.WINDOWS;
    private static final Instant FINISHED = Instant.parse("2026-09-14T10:00:00Z");

    @TempDir
    Path tmp;
    Path dest;
    InstallManifest manifest;
    Platform platform;
    InstallationRegistry registry;
    List<String> events;
    ProgressListener listener;

    @BeforeEach
    void setUp() throws IOException {
        dest = Files.createDirectories(tmp.resolve("dest"));
        manifest = TestManifests.parse("/manifest/engine/rollback.xml");
        platform = PlatformFactory.current();
        registry = InstallationRegistry.at(tmp.resolve("registry"), "rb");
        events = new ArrayList<>();
        listener = new ProgressListener() {
            @Override
            public void uninstallStarted(int total) {
                events.add("start " + total);
            }

            @Override
            public void uninstallProgress(int done, int total, String what) {
                events.add(done + "/" + total + " " + what);
            }

            @Override
            public void uninstallFinished(Uninstaller.Report report) {
                events.add("end");
            }
        };
    }

    /**
     * An installation as the steps would have recorded it: a directory with
     * two files, a replaced file, a registered command - written through the
     * writer so {@code .installer/record.xml} exists and is recorded first.
     * Every file's modification time is set before the run's end.
     */
    private InstallationRecord installed(boolean withFinished) throws IOException {
        InstallationRecord record = new InstallationRecord("rb", "1", FINISHED.minusSeconds(60), dest,
                List.of("core"), Map.of());
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(dest), record)) {
            Files.createDirectories(dest.resolve("made/sub"));
            record.directoryCreated(dest.resolve("made"));
            record.directoryCreated(dest.resolve("made/sub"));
            write(dest.resolve("made/sub/a.txt"), "a\n");
            record.fileCreated(dest.resolve("made/sub/a.txt"));
            write(dest.resolve("made/b.txt"), "b\n");
            record.fileCreated(dest.resolve("made/b.txt"));
            write(dest.resolve("etc/app.ini"), "new\n");
            record.fileReplaced(dest.resolve("etc/app.ini"), dest.resolve(".installer/backup/x/etc/app.ini"));
            record.stepStarted("register");
            write(dest.resolve("registered"), "yes\n");
            record.stepStarted("no-reverse");
            record.stepFinished("no-reverse", "DONE", null);
            if (withFinished) {
                record.runFinished(FINISHED);
            }
        }
        registry.register(record);
        return RecordReader.read(RecordWriter.defaultFile(dest));
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(FINISHED.minusSeconds(30)));
    }

    private Path touchLater(String relative, String content) throws IOException {
        Path file = dest.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(FINISHED.plusSeconds(600)));
        return file;
    }

    private Uninstaller uninstaller(InstallationRecord record) {
        return new Uninstaller(manifest, platform, record, Optional.of(registry));
    }

    @Test
    void inventoryListsInstalledChangedAndForeignFilesAndTheSteps() throws IOException {
        InstallationRecord record = installed(true);
        touchLater("etc/app.ini", "edited\n");
        touchLater("saves/game1.sav", "user data\n");

        Uninstaller.Inventory inventory = uninstaller(record).inspect();

        assertEquals(List.of("made/sub/a.txt", "made/b.txt", "etc/app.ini"), inventory.files(),
                "replaced files count as installed; .installer is handled separately");
        assertEquals(List.of("etc/app.ini"), inventory.modified());
        assertEquals(List.of("saves/game1.sav", "registered"), inventory.unrecorded(),
                "a run-command records nothing; its mark is a foreign file until its reverse removes it");
        assertEquals(List.of("made", "made/sub"), inventory.directories());
        assertEquals(List.of("no-reverse", "register"), inventory.steps(), "newest first");
        assertEquals(List.of("no-reverse"), inventory.stepsWithoutReverse());
        assertEquals(FINISHED, inventory.finishedAt());
    }

    @Test
    void safeRunRemovesTheInstallationAndKeepsWhatTheUserChangedOrAdded() throws IOException {
        assumeTrue(POSIX, "/bin/sh");
        InstallationRecord record = installed(true);
        touchLater("etc/app.ini", "edited\n");
        touchLater("saves/game1.sav", "user data\n");

        Uninstaller.Report report = uninstaller(record).run(Uninstaller.Options.SAFE, listener, new CancellationToken());

        assertTrue(report.clean(), report.failures().toString());
        assertEquals(1, report.stepsReversed());
        assertEquals(List.of("no-reverse"), report.stepsWithoutReverse());
        assertEquals(List.of("etc/app.ini"), report.keptModified());
        // The reverse command ran before the files went: it removed 'registered' and wrote 'unregistered'.
        assertFalse(Files.exists(dest.resolve("registered")));
        assertTrue(Files.exists(dest.resolve("unregistered")), "written by the reverse command, kept as user data");
        assertEquals(Set.of("saves/game1.sav", "unregistered"), Set.copyOf(report.keptUnrecorded()),
                "foreign files are listed after the reverse commands ran");
        assertFalse(Files.exists(dest.resolve("made")), "recorded files and emptied directories are gone");
        assertTrue(Files.exists(dest.resolve("etc/app.ini")), "changed since the installation");
        assertTrue(Files.exists(dest.resolve("saves/game1.sav")), "not ours");
        assertFalse(Files.exists(dest.resolve(".installer")), "record and backups go last");
        assertTrue(registry.list().isEmpty(), "unregistered");
        assertFalse(report.destinationRemoved());
        assertTrue(Files.isDirectory(dest));
        assertEquals("start " + (2 + 3 + 2 + 2 + 1), events.get(0), "steps, files, .installer + register, dirs, dest");
        assertEquals("end", events.get(events.size() - 1));
        assertEquals(2 + 3 + 2 + 2 + 1 + 2, events.size(), "one progress event per item");
    }

    @Test
    void purgeRemovesEverythingIncludingTheDestination() throws IOException {
        assumeTrue(POSIX, "/bin/sh");
        InstallationRecord record = installed(true);
        touchLater("etc/app.ini", "edited\n");
        touchLater("saves/game1.sav", "user data\n");

        Uninstaller.Report report = uninstaller(record).run(Uninstaller.Options.PURGE, listener, new CancellationToken());

        assertTrue(report.clean(), report.failures().toString());
        assertTrue(report.keptModified().isEmpty());
        assertTrue(report.keptUnrecorded().isEmpty());
        assertTrue(report.destinationRemoved(), "even what the reverse command wrote is gone");
        assertFalse(Files.exists(dest));
    }

    @Test
    void withoutARegisteredCommandTheDestinationGoesToo() throws IOException {
        InstallationRecord record = new InstallationRecord("rb", "1", FINISHED.minusSeconds(60), dest,
                List.of("core"), Map.of());
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(dest), record)) {
            write(dest.resolve("a.txt"), "a\n");
            record.fileCreated(dest.resolve("a.txt"));
            record.runFinished(FINISHED);
        }
        registry.register(record);

        Uninstaller.Report report = uninstaller(RecordReader.read(RecordWriter.defaultFile(dest)))
                .run(Uninstaller.Options.SAFE, listener, new CancellationToken());

        assertTrue(report.clean());
        assertTrue(report.destinationRemoved());
        assertFalse(Files.exists(dest));
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void aRecordWithoutTheRunsEndFallsBackToTheRecordFilesTime() throws IOException {
        InstallationRecord record = installed(false);
        Path recordFile = RecordWriter.defaultFile(dest);
        Files.setLastModifiedTime(recordFile, FileTime.from(FINISHED));
        touchLater("made/b.txt", "edited\n");

        Uninstaller.Inventory inventory = uninstaller(record).inspect();

        assertEquals(FINISHED, inventory.finishedAt());
        assertEquals(List.of("made/b.txt"), inventory.modified());
    }

    @Test
    void aDirectoryFromAnUpdateThatIsNotRecordedIsPrunedWhenEmptied() throws IOException {
        // An update's record knows the file it replaced but not the directory the first installation made.
        InstallationRecord record = new InstallationRecord("rb", "2", FINISHED.minusSeconds(60), dest,
                List.of("core"), Map.of());
        Files.createDirectories(dest.resolve("old/dir"));
        write(dest.resolve("old/dir/data.bin"), "v2\n");
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(dest), record)) {
            record.fileReplaced(dest.resolve("old/dir/data.bin"), dest.resolve(".installer/backup/x/old/dir/data.bin"));
            record.runFinished(FINISHED);
        }

        Uninstaller.Report report = new Uninstaller(manifest, platform, RecordReader.read(RecordWriter.defaultFile(dest)),
                Optional.empty()).run(Uninstaller.Options.SAFE, listener, new CancellationToken());

        assertTrue(report.clean());
        assertFalse(Files.exists(dest.resolve("old")), "emptied parents go, up to the destination");
        assertTrue(report.destinationRemoved());
    }

    @Test
    void aFailureKeepsTheRecordAndTheRegisterEntryForASecondAttempt() throws IOException {
        assumeTrue(POSIX, "read-only directories");
        InstallationRecord record = installed(true);
        Path locked = dest.resolve("made/sub");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            Uninstaller.Report report = uninstaller(record).run(Uninstaller.Options.SAFE, listener,
                    new CancellationToken());

            assertFalse(report.clean());
            assertEquals(1, report.failures().size(), report.failures().toString());
            assertTrue(report.failures().get(0).contains("a.txt"));
            assertTrue(Files.exists(RecordWriter.defaultFile(dest)), "record stays for a retry");
            assertEquals(1, registry.list().size(), "still registered");
            assertFalse(Files.exists(dest.resolve("made/b.txt")), "the rest was removed anyway");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void cancellationStopsWithoutRestoringAnything() throws IOException {
        InstallationRecord record = installed(true);
        CancellationToken token = new CancellationToken();
        ProgressListener cancelling = new ProgressListener() {
            @Override
            public void uninstallProgress(int done, int total, String what) {
                if (what.equals("made/sub/a.txt")) {
                    token.cancel();
                }
            }
        };

        Uninstaller.Report report = uninstaller(record).run(Uninstaller.Options.SAFE, cancelling, token);

        assertTrue(report.cancelled());
        assertFalse(report.clean());
        assertFalse(Files.exists(dest.resolve("made/sub/a.txt")), "deleted before the cancel");
        assertTrue(Files.exists(dest.resolve("made/b.txt")), "not reached");
        assertTrue(Files.exists(RecordWriter.defaultFile(dest)));
        assertEquals(1, registry.list().size());
    }

    @Test
    void dryRunNamesEveryActionAndChangesNothing() throws IOException {
        InstallationRecord record = installed(true);
        touchLater("etc/app.ini", "edited\n");
        touchLater("saves/game1.sav", "user data\n");

        List<String> lines = uninstaller(record).dryRun(Uninstaller.Options.SAFE);

        assertTrue(lines.contains("step register: run reverse commands"), lines.toString());
        assertTrue(lines.contains("step no-reverse: no reverse operation, cannot be undone"));
        assertTrue(lines.contains("delete made/sub/a.txt"));
        assertTrue(lines.contains("keep etc/app.ini (changed since the installation)"));
        assertTrue(lines.contains("keep saves/game1.sav (not installed by the installer)"));
        assertTrue(lines.contains("remove made/sub if empty"));
        assertEquals("remove " + dest + " if empty", lines.get(lines.size() - 1));
        assertTrue(Files.exists(dest.resolve("made/sub/a.txt")));
        assertTrue(Files.exists(RecordWriter.defaultFile(dest)));
        assertEquals(1, registry.list().size());
    }
}
