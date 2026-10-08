package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordWriter;
import de.yawi.installer.core.engine.step.Steps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E11-S05: the record walked backwards puts the destination back the way it was. */
class RollbackTest {

    private static final boolean POSIX = PlatformFactory.current().os() != OperatingSystem.WINDOWS;

    @TempDir
    Path tmp;
    Path dest;
    InstallManifest manifest;
    Platform platform;
    ExecutionContext context;
    ExecutionPlan plan;
    Backup backup;
    List<String> events;

    @BeforeEach
    void setUp() throws IOException {
        dest = Files.createDirectories(tmp.resolve("dest"));
        manifest = TestManifests.parse("/manifest/engine/rollback.xml");
        platform = PlatformFactory.current();
        events = new ArrayList<>();
        ProgressListener listener = new ProgressListener() {
            @Override
            public void rollbackStarted(int total) {
                events.add("start " + total);
            }

            @Override
            public void rollbackProgress(int done, int total, String what) {
                events.add(done + "/" + total);
            }

            @Override
            public void rollbackFinished(Rollback.Report report) {
                events.add("end");
            }
        };
        context = EngineTestSupport.context(manifest, platform, dest, Map.of(), Set.of("core"), listener, null,
                new CancellationToken());
        plan = ExecutionPlan.build(manifest, Set.of("core"), platform, context.placeholders(), Steps.DEFAULT);
        backup = Backup.of(context);
    }

    private Rollback rollback(Optional<Path> previousRecord, boolean destinationCreated) {
        return new Rollback(context, plan, backup, previousRecord, destinationCreated);
    }

    private InstallationRecord record() {
        return context.record();
    }

    @Test
    void undoesCreatedReplacedAndChangedInReverseOrderAndKeepsWhatWasThere() throws IOException {
        assumeTrue(POSIX, "modes and /bin/sh");
        // Before the run: a foreign file and a file the run will replace.
        Files.writeString(dest.resolve("foreign.txt"), "not ours\n");
        Files.createDirectories(dest.resolve("etc"));
        Files.writeString(dest.resolve("etc/app.ini"), "old\n");
        Files.setPosixFilePermissions(dest.resolve("etc/app.ini"), PosixFilePermissions.fromString("rw-r--r--"));

        // The run, as the steps would have recorded it.
        record().directoryCreated(dest.resolve("made"));
        Files.createDirectories(dest.resolve("made/sub"));
        record().directoryCreated(dest.resolve("made/sub"));
        Files.writeString(dest.resolve("made/sub/new.txt"), "new\n");
        record().fileCreated(dest.resolve("made/sub/new.txt"));
        Path saved = backup.save(dest.resolve("etc/app.ini")).orElseThrow();
        record().fileReplaced(dest.resolve("etc/app.ini"), saved);
        Files.writeString(dest.resolve("etc/app.ini"), "new\n");
        record().modeChanged(dest.resolve("etc/app.ini"), "644");
        Files.setPosixFilePermissions(dest.resolve("etc/app.ini"), PosixFilePermissions.fromString("rwxr-x---"));
        record().stepStarted("register");
        Files.writeString(dest.resolve("registered"), "yes\n");
        record().stepFinished("register", "DONE", null);
        record().stepStarted("no-reverse");
        record().stepFinished("no-reverse", "DONE", null);
        record().stepStarted("boom");
        record().stepFinished("boom", "FAILED", "exit code 2");

        Rollback.Report report = rollback(Optional.empty(), false).run();

        assertTrue(report.clean(), report.toString());
        // Newest first; the failed step counts too - it may have done half its work.
        assertEquals(List.of("boom", "no-reverse"), report.stepsWithoutReverse());
        assertEquals("not ours\n", Files.readString(dest.resolve("foreign.txt")), "foreign files stay");
        assertEquals("old\n", Files.readString(dest.resolve("etc/app.ini")), "replaced file is back");
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(dest.resolve("etc/app.ini")));
        assertTrue(Files.isDirectory(dest.resolve("etc")), "pre-existing directory stays");
        assertFalse(Files.exists(dest.resolve("made")), "created directories are gone");
        assertFalse(Files.exists(dest.resolve("registered")), "the reverse command ran");
        assertEquals("unregistered\n", Files.readString(dest.resolve("unregistered")));
        assertFalse(Files.exists(dest.resolve(".installer/backup")), "backup folder pruned away");
        assertTrue(report.restored() >= 3 && report.deleted() >= 3, report.toString());
        assertEquals("start " + record().entries().size(), events.get(0));
        assertEquals("end", events.get(events.size() - 1));
        assertTrue(events.contains(record().entries().size() + "/" + record().entries().size()), events.toString());
    }

    @Test
    void reverseCommandRunsForAStepThatNeverFinished() throws IOException {
        assumeTrue(POSIX, "/bin/sh");
        record().stepStarted("register");
        Files.writeString(dest.resolve("registered"), "half\n");
        // no stepFinished: the process was killed by the cancellation

        Rollback.Report report = rollback(Optional.empty(), false).run();

        assertTrue(report.clean(), report.toString());
        assertFalse(Files.exists(dest.resolve("registered")));
    }

    @Test
    void failuresAreCollectedAndTheRestStillRuns() throws IOException {
        Files.writeString(dest.resolve("keep.txt"), "x\n");
        record().directoryCreated(dest.resolve(".installer"));
        record().fileCreated(dest.resolve(".installer/record.xml"));
        Files.createDirectories(dest.resolve(".installer"));
        Files.writeString(dest.resolve(".installer/record.xml"), "<record/>\n");
        // A replacement whose backup vanished, and a created file that is fine.
        record().fileReplaced(dest.resolve("gone.txt"), backup.root().resolve("gone.txt"));
        Files.writeString(dest.resolve("gone.txt"), "installed\n");
        Files.writeString(dest.resolve("new.txt"), "new\n");
        record().fileCreated(dest.resolve("new.txt"));

        Rollback.Report report = rollback(Optional.empty(), false).run();

        assertFalse(report.clean());
        assertEquals(1, report.failures().size(), report.failures().toString());
        assertTrue(report.failures().get(0).contains("gone.txt"), report.failures().get(0));
        assertFalse(Files.exists(dest.resolve("new.txt")), "the rest was still undone");
        assertTrue(Files.exists(dest.resolve("keep.txt")));
        assertFalse(Files.exists(dest.resolve(".installer/record.xml")), "the run's own record goes too");
    }

    @Test
    void previousRecordComesBackAndTheDestinationGoesWhenTheRunCreatedIt() throws IOException {
        Path recordFile = RecordWriter.defaultFile(dest);
        Files.createDirectories(recordFile.getParent());
        Files.writeString(recordFile, "<record version=\"1\" productVersion=\"new\"/>\n");
        Path copy = backup.root().resolve(RecordWriter.DIRECTORY).resolve(RecordWriter.FILE_NAME);
        Files.createDirectories(copy.getParent());
        Files.writeString(copy, "<record version=\"1\" productVersion=\"old\"/>\n");

        Rollback.Report report = rollback(Optional.of(copy), false).run();
        assertTrue(report.clean(), report.toString());
        assertTrue(Files.readString(recordFile).contains("old"), "previous record restored");
        assertFalse(Files.exists(backup.root()));

        // A fresh install into a folder that did not exist: everything, including the folder, disappears.
        Path fresh = tmp.resolve("fresh");
        ExecutionContext freshContext = EngineTestSupport.context(manifest, platform, fresh, Map.of(), Set.of("core"),
                ProgressListener.NOOP, null, new CancellationToken());
        Files.createDirectories(fresh.resolve(".installer"));
        freshContext.record().directoryCreated(fresh.resolve(".installer"));
        Files.writeString(fresh.resolve(".installer/record.xml"), "<record/>\n");
        freshContext.record().fileCreated(fresh.resolve(".installer/record.xml"));
        Rollback.Report freshReport = new Rollback(freshContext, plan, Backup.of(freshContext), Optional.empty(), true).run();
        assertTrue(freshReport.clean(), freshReport.toString());
        assertFalse(Files.exists(fresh));
    }

    @Test
    void anInterruptedThreadDoesNotStopTheRollback() throws IOException {
        assumeTrue(POSIX, "/bin/sh");
        record().stepStarted("register");
        Files.writeString(dest.resolve("registered"), "yes\n");
        record().stepFinished("register", "DONE", null);
        Thread.currentThread().interrupt();
        try {
            Rollback.Report report = rollback(Optional.empty(), false).run();
            assertTrue(report.clean(), report.toString());
            assertFalse(Files.exists(dest.resolve("registered")));
        } finally {
            Thread.interrupted();
        }
    }
}
