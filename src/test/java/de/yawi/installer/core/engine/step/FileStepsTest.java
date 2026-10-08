package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Backup;
import de.yawi.installer.core.engine.ExecutableStep;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.step.StepTestSupport.Capture;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.SourceUnavailableException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integrity.PathEscapeException;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.state.InstallationRecord.ModeChanged;
import de.yawi.installer.core.state.InstallationRecord.ReplacedFile;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** E11-S02: extract, copy, template, mkdir, chmod against a temp destination. */
class FileStepsTest {

    private static final boolean POSIX = PlatformFactory.current().os() != OperatingSystem.WINDOWS;

    @TempDir
    Path tmp;
    Path dest;
    Capture capture;
    ExecutionContext context;

    @BeforeEach
    void setUp() throws Exception {
        dest = Files.createDirectories(tmp.resolve("dest"));
        capture = new Capture();
        context = StepTestSupport.context(dest, Map.of("port", "9090"), capture);
    }

    private ExecutableStep step(String id) {
        InstallStep definition = context.manifest().step(id).orElseThrow();
        return Steps.DEFAULT.create(definition);
    }

    private List<String> created() {
        return context.record().createdPaths();
    }

    // --- extract -----------------------------------------------------------

    @Test
    void extractsZipWithSubdirectoriesAndUmlauts() throws Exception {
        step("unzip").execute(context);

        assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")));
        assertEquals("MAP2\n", Files.readString(dest.resolve("data/maps/level2.map")));
        assertEquals("Grüße\n", Files.readString(dest.resolve("docs/ümlaut.txt")));
        assertTrue(Files.isDirectory(dest.resolve("bin")));
        assertTrue(created().contains("README.txt"), created().toString());
        assertTrue(created().contains("data/maps/level2.map"));
        assertTrue(created().contains("data"), "directories are recorded too: " + created());
        assertTrue(created().indexOf("data") < created().indexOf("data/maps"), "outer directories first");
        // Progress follows the bytes read against source/@size.
        assertTrue(capture.fractions.stream().anyMatch(f -> f > 0 && f <= 1), capture.fractions.toString());
        assertEquals(1.0, capture.fractions.get(capture.fractions.size() - 1));
    }

    @Test
    void extractsTarGzWithModesAndLongNames() throws Exception {
        step("untar").execute(context);
        Path tar = dest.resolve("tar");

        assertEquals("sample archive\n", Files.readString(tar.resolve("README.txt")));
        assertEquals("MAP1\n", Files.readString(tar.resolve("data/maps/level1.map")));
        assertEquals("Grüße\n", Files.readString(tar.resolve("docs/ümlaut.txt")));
        assertEquals("long\n", Files.readString(tar.resolve("deep/" + "x".repeat(120) + "/long.txt")), "GNU long name");
        if (POSIX) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tar.resolve("bin/run.sh"));
            assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE), perms.toString());
            assertFalse(Files.getPosixFilePermissions(tar.resolve("README.txt")).contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTrue(created().contains("tar/bin/run.sh"), created().toString());
    }

    @Test
    void zipSlipAndTarSlipAreRejected() {
        PathEscapeException zip = assertThrows(PathEscapeException.class, () -> step("zip-slip").execute(context));
        assertTrue(zip.getMessage().contains("evil.txt"), zip.getMessage());
        assertTrue(Files.exists(dest.resolve("slip/ok.txt")), "entries before the bad one were written");
        assertFalse(Files.exists(dest.resolve("evil.txt")));

        assertThrows(PathEscapeException.class, () -> step("tar-slip").execute(context));
        assertFalse(Files.exists(dest.resolve("evil.txt")));
    }

    @Test
    void symlinkEntriesAreSkippedNotFollowed() throws Exception {
        step("symlink").execute(context);
        assertFalse(Files.exists(dest.resolve("sym/link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("after\n", Files.readString(dest.resolve("sym/after.txt")), "entries after the link still arrive");
        assertTrue(capture.output.stream().anyMatch(l -> l.contains("skipped link")), capture.output.toString());
    }

    @Test
    void targetOutsideTheDestinationIsRejectedButTheWorkDirIsAllowed() throws Exception {
        assertThrows(PathEscapeException.class, () -> step("escape").execute(context));

        Path work = context.workDir().resolve("su-step-test");
        try {
            step("to-work-dir").execute(context);
            assertTrue(Files.isDirectory(work));
            assertTrue(created().contains(work.toString()), "outside the destination: recorded absolute " + created());
        } finally {
            Files.deleteIfExists(work);
        }
    }

    @Test
    void extractsAStandaloneExecutableAsIsWithTheExecutableBitSet() throws Exception {
        step("raw").execute(context);

        Path exe = dest.resolve("raw/tool.exe");
        assertEquals("not a real executable, just bytes for the raw-extract test\n", Files.readString(exe));
        if (POSIX) {
            assertTrue(Files.getPosixFilePermissions(exe).contains(PosixFilePermission.OWNER_EXECUTE));
        }
        assertTrue(created().contains("raw/tool.exe"), created().toString());
    }

    @Test
    void sourceWithoutBundledProviderIsUnavailable() {
        assertThrows(SourceUnavailableException.class, () -> step("no-bundled").execute(context));
    }

    @Test
    void cancelDuringExtractStops() throws Exception {
        CancellationToken token = new CancellationToken();
        ExecutionContext cancelling = StepTestSupport.context(context.manifest(), dest, Map.of(), new Capture() {
            @Override
            public void stepProgress(double fraction, String message) {
                token.cancel(); // after the first entry
            }
        }, context.artifacts(), token);
        assertThrows(CancelledException.class, () -> step("unzip").execute(cancelling));
        assertFalse(Files.exists(dest.resolve("docs/ümlaut.txt")), "later entries were not written");
    }

    // --- copy ----------------------------------------------------------------

    @Test
    void copiesTreesFilesAndResourcesKeepingAttributes() throws Exception {
        step("unzip").execute(context);
        if (POSIX) {
            Files.setPosixFilePermissions(dest.resolve("data/maps/level1.map"), PosixFilePermissions.fromString("rwxr-x---"));
        }
        context.record().entries(); // baseline irrelevant; createdPaths checked below

        step("copy-tree").execute(context);
        step("copy-file").execute(context);
        step("copy-resource").execute(context);

        assertEquals("MAP1\n", Files.readString(dest.resolve("copy/data/maps/level1.map")));
        assertEquals("sample archive\n", Files.readString(dest.resolve("copy/README.txt")));
        assertTrue(Files.readString(dest.resolve("copy/settings.ini")).contains("${PORT}"), "resource copied verbatim");
        if (POSIX) {
            assertTrue(Files.getPosixFilePermissions(dest.resolve("copy/data/maps/level1.map"))
                    .contains(PosixFilePermission.OWNER_EXECUTE), "attributes copied");
        }
        assertTrue(created().contains("copy/data/maps/level1.map"), created().toString());
        assertTrue(created().contains("copy"), created().toString());

        // Copying again replaces without recording the files a second time.
        int before = created().size();
        step("copy-file").execute(context);
        assertEquals(before, created().size());
    }

    @Test
    void copyOfAMissingSourceFails() {
        StepFailedException e = assertThrows(StepFailedException.class, () -> step("copy-missing").execute(context));
        assertEquals("copy-missing", e.stepId());
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    // --- template ------------------------------------------------------------

    @Test
    void templateReplacesKeysWithResolvedValues() throws Exception {
        step("template").execute(context);
        String text = Files.readString(dest.resolve("etc/settings.ini"));
        assertTrue(text.contains("installDir=" + dest), text);
        assertTrue(text.contains("port=9090"), text);
        assertFalse(text.contains("${"), text);
        assertEquals(List.of("etc", "etc/settings.ini"), created());
    }

    @Test
    void templateHonoursTheEncoding() throws Exception {
        Files.write(dest.resolve("latin1.txt"), "name=${NAME}\n".getBytes(StandardCharsets.ISO_8859_1));
        step("template-latin1").execute(context);
        byte[] out = Files.readAllBytes(dest.resolve("latin1.out"));
        assertEquals("name=Grüße\n", new String(out, StandardCharsets.ISO_8859_1));
        assertEquals(11, out.length, "one byte per character in Latin-1");

        StepFailedException e = assertThrows(StepFailedException.class, () -> step("template-bad-encoding").execute(context));
        assertTrue(e.getMessage().contains("no-such-charset"), e.getMessage());
    }

    // --- mkdir / chmod ---------------------------------------------------------

    @Test
    void mkdirCreatesParentsAndRecordsOnlyNewOnes() throws Exception {
        Files.createDirectories(dest.resolve("a"));
        step("mkdir").execute(context);
        assertTrue(Files.isDirectory(dest.resolve("a/b/c")));
        assertEquals(List.of("a/b", "a/b/c"), created());

        step("mkdir").execute(context);
        assertEquals(List.of("a/b", "a/b/c"), created(), "second run adds nothing");
    }

    // --- backup of replaced files ---------------------------------

    private List<ReplacedFile> replaced() {
        return context.record().entries().stream()
                .filter(ReplacedFile.class::isInstance).map(ReplacedFile.class::cast).toList();
    }

    @Test
    void replacedFileIsMovedToTheBackupAndRecorded() throws Exception {
        Files.writeString(dest.resolve("README.txt"), "the original\n");
        Files.createDirectories(dest.resolve("etc"));
        Files.writeString(dest.resolve("etc/settings.ini"), "old=1\n");
        Files.createDirectories(dest.resolve("copy"));
        Files.writeString(dest.resolve("copy/settings.ini"), "older\n");

        step("unzip").execute(context);          // extract over README.txt
        step("template").execute(context);       // template over etc/settings.ini
        step("copy-resource").execute(context);  // copy over copy/settings.ini

        assertEquals("sample archive\n", Files.readString(dest.resolve("README.txt")));
        Path backupRoot = Backup.rootFor(dest, context.record().startedAt());
        assertEquals("the original\n", Files.readString(backupRoot.resolve("README.txt")));
        assertEquals("old=1\n", Files.readString(backupRoot.resolve("etc/settings.ini")));
        assertEquals("older\n", Files.readString(backupRoot.resolve("copy/settings.ini")));
        List<String> replacedPaths = replaced().stream().map(ReplacedFile::path).toList();
        assertEquals(List.of("README.txt", "etc/settings.ini", "copy/settings.ini"), replacedPaths);
        assertTrue(replaced().get(0).backup().startsWith(".installer/backup/"), replaced().get(0).backup());
        assertFalse(created().contains("README.txt"), "a replaced file is not a created one: " + created());
        assertFalse(created().contains("etc/settings.ini"));
        assertTrue(created().contains("data/maps/level2.map"), "new files are still recorded");
    }

    @Test
    void aPathReplacedTwiceIsBackedUpOnce() throws Exception {
        Files.createDirectories(dest.resolve("copy"));
        Files.writeString(dest.resolve("copy/settings.ini"), "the original\n");
        step("copy-resource").execute(context);
        Files.writeString(dest.resolve("copy/settings.ini"), "intermediate\n");
        step("copy-resource").execute(context);

        Path backupRoot = Backup.rootFor(dest, context.record().startedAt());
        assertEquals("the original\n", Files.readString(backupRoot.resolve("copy/settings.ini")));
        assertEquals(1, replaced().size(), replaced().toString());
    }

    @Test
    void fileCreatedThenOverwrittenIsNotBackedUp() throws Exception {
        step("copy-resource").execute(context);
        step("copy-resource").execute(context);
        assertTrue(replaced().isEmpty(), replaced().toString());
        assertEquals(1, created().stream().filter("copy/settings.ini"::equals).count());
        assertFalse(Files.exists(dest.resolve(".installer")), "no backup folder without a replacement");
    }

    @Test
    void chmodRecordsTheOldMode() throws Exception {
        assumeTrue(POSIX, "no POSIX permissions on Windows");
        Files.createDirectories(dest.resolve("a"));
        Files.writeString(dest.resolve("a/script.sh"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(dest.resolve("a/script.sh"), PosixFilePermissions.fromString("rw-r--r--"));
        step("chmod").execute(context);
        assertEquals(List.of(new ModeChanged("a/script.sh", "644")),
                context.record().entries().stream().filter(ModeChanged.class::isInstance).toList());
    }

    @Test
    void symlinkTargetIsBackedUpAsTheLinkNotItsTarget() throws Exception {
        assumeTrue(POSIX, "symlinks need POSIX");
        Files.writeString(dest.resolve("real.ini"), "real\n");
        Files.createDirectories(dest.resolve("copy"));
        Files.createSymbolicLink(dest.resolve("copy/settings.ini"), dest.resolve("real.ini"));
        step("copy-resource").execute(context);
        Path backupRoot = Backup.rootFor(dest, context.record().startedAt());
        assertTrue(Files.isSymbolicLink(backupRoot.resolve("copy/settings.ini")), "the link itself went to the backup");
        assertTrue(Files.isRegularFile(dest.resolve("copy/settings.ini"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("real\n", Files.readString(dest.resolve("real.ini")), "the link's target is untouched");
        assertEquals(1, replaced().size());
    }

    @Test
    void chmodSetsTheModeOnPosix() throws Exception {
        assumeTrue(POSIX, "no POSIX permissions on Windows");
        Files.createDirectories(dest.resolve("a"));
        Files.writeString(dest.resolve("a/script.sh"), "#!/bin/sh\n");
        step("chmod").execute(context);
        assertEquals(PosixFilePermissions.fromString("rwxr-x---"), Files.getPosixFilePermissions(dest.resolve("a/script.sh")));

        StepFailedException e = assertThrows(StepFailedException.class, () -> step("chmod-missing").execute(context));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void modeParsing() {
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), ChmodStep.permissions("755"));
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), ChmodStep.permissions("0644"));
        assertEquals(PosixFilePermissions.fromString("rwx------"), ChmodStep.permissions("4700"), "setuid bit ignored");
    }

    @Test
    void dryRunDescriptionsResolvePlaceholders() {
        assertEquals("extract zip -> " + dest, step("unzip").describe(context));
        assertEquals("template classpath:/templates/settings.ini -> " + dest.resolve("etc/settings.ini") + " [INSTALL_DIR, PORT]",
                step("template").describe(context));
        assertEquals("mkdir " + dest.resolve("a/b/c"), step("mkdir").describe(context));
        assertEquals("chmod 750 " + dest.resolve("a/script.sh"), step("chmod").describe(context));
        assertEquals("copy " + dest.resolve("data") + " -> " + dest.resolve("copy/data"), step("copy-tree").describe(context));
    }

    @Test
    void recordEntriesArriveInTheWriterAsTheyHappen() throws Exception {
        assertTrue(TestManifests.parse("/manifest/engine/file-steps.xml").step("unzip").isPresent());
        InstallationRecord record = context.record();
        List<InstallationRecord.Entry> seen = new java.util.ArrayList<>();
        record.onEntry(seen::add);
        step("mkdir").execute(context);
        assertEquals(3, seen.size(), seen.toString());
    }
}
