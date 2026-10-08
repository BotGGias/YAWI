package de.yawi.installer.core.engine;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E14-S04: taking a deselected component back. Installs {@code server} (which
 * pulls {@code core} in), then removes the steps that only {@code server} owns
 * and checks the server files are gone while the core files stay.
 */
class ComponentRemovalTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private Path home;
    private Path dest;
    private final InstallManifest manifest = TestManifests.bundled();

    @BeforeEach
    void setUp() {
        assumeTrue(PlatformFactory.current().os() == OperatingSystem.LINUX);
        home = tmp.resolve("home");
        dest = tmp.resolve("dest");
        platform = TestPlatforms.scratch(home);
    }

    private InstallationRecord installServerAndCore() throws Exception {
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, Set.of("server"),
                Map.of(), Map.of(), Optional.empty());
        Engine.Result result = new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        return RecordReader.read(RecordWriter.defaultFile(dest));
    }

    @Test
    void removedStepIdsAreTheOnesOnlyTheDroppedComponentUses() {
        Set<String> removed = ComponentRemoval.removedStepIds(manifest, platform,
                manifest.resolveSelection(Set.of("server")), manifest.resolveSelection(Set.of("core")));
        assertTrue(removed.contains("install-server"), removed.toString());
        assertTrue(removed.contains("make-server-executable"), removed.toString());
        assertTrue(removed.contains("register-service"), removed.toString());
        assertFalse(removed.contains("unpack-game-data"), removed.toString());
        assertFalse(removed.contains("write-settings"), removed.toString());
    }

    @Test
    void removingTheServerDeletesItsFilesAndKeepsCore() throws Exception {
        InstallationRecord record = installServerAndCore();
        assertTrue(Files.isRegularFile(dest.resolve("bin/su-server")), "server installed");

        Set<String> removed = ComponentRemoval.removedStepIds(manifest, platform,
                manifest.resolveSelection(Set.of("server")), manifest.resolveSelection(Set.of("core")));
        ComponentRemoval.Report report = new ComponentRemoval(manifest, platform, record)
                .run(removed, ProgressListener.NOOP, new CancellationToken());

        assertTrue(report.clean(), report.failures().toString());
        assertTrue(report.stepsReversed() >= 1, "register-service was reversed");
        assertFalse(Files.exists(dest.resolve("bin")), "the server tree is gone");
        assertTrue(Files.isRegularFile(dest.resolve("settings.ini")), "core's settings stay");
        assertTrue(Files.isRegularFile(dest.resolve("data/maps/green-valley.map")), "core's data stays");
    }

    @Test
    void nothingRemovedWhenTheSelectionIsUnchanged() throws Exception {
        InstallationRecord record = installServerAndCore();
        Set<String> removed = ComponentRemoval.removedStepIds(manifest, platform,
                manifest.resolveSelection(Set.of("server")), manifest.resolveSelection(Set.of("server")));
        ComponentRemoval.Report report = new ComponentRemoval(manifest, platform, record)
                .run(removed, ProgressListener.NOOP, new CancellationToken());
        assertTrue(report.removed() == 0 && report.clean(), report.toString());
        assertTrue(Files.isRegularFile(dest.resolve("bin/su-server")), "server stays");
    }
}
