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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E14-S04: repair reinstalls the recorded selection into the same folder,
 * restoring a file that was deleted or changed after the installation. Without
 * per-file checksums this is a plain reinstall (idempotent overwrite).
 */
class RepairLifecycleTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private Path dest;
    private final InstallManifest manifest = TestManifests.bundled();

    @BeforeEach
    void setUp() {
        assumeTrue(PlatformFactory.current().os() == OperatingSystem.LINUX);
        platform = TestPlatforms.scratch(tmp.resolve("home"));
        dest = tmp.resolve("dest");
    }

    private Engine.Result install(Set<String> components) {
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, components,
                Map.of(), Map.of(), Optional.empty());
        return new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    @Test
    void repairRestoresADeletedFile() throws Exception {
        assertTrue(install(Set.of("core")).succeeded());
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        Set<String> recorded = Set.copyOf(record.components());

        Path map = dest.resolve("data/maps/green-valley.map");
        assertTrue(Files.isRegularFile(map), "installed");
        Files.delete(map);
        assertFalse(Files.exists(map), "deleted by hand");

        // Repair = reinstall the recorded selection into the same folder.
        assertTrue(install(recorded).succeeded());
        assertTrue(Files.isRegularFile(map), "repair restored the file");

        InstallationRecord after = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals(record.components(), after.components(), "the record still describes the same selection");
    }
}
