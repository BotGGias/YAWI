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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E13-S02 through the runner: the file association is created with the
 * installation and removed - files, the MIME cache and the mimeapps.list
 * default - by the uninstaller. Linux; the home is the temp folder.
 */
class AssociationLifecycleTest {

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

    private Engine.Result install(Collection<String> associations) {
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, Set.of("server"),
                Map.of(), Map.of(), associations, Optional.empty());
        return new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    private Path mimePackage() {
        return home.resolve(".local/share/mime/packages/your-product-sumap.xml");
    }

    private Path handler() {
        return home.resolve(".local/share/applications/your-product-sumap.desktop");
    }

    @Test
    void installCreatesTheAssociationAndUninstallRemovesIt() throws Exception {
        Engine.Result result = install(manifest.integration().defaultAssociationExtensions());
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        assertTrue(Files.isRegularFile(mimePackage()), mimePackage().toString());
        assertTrue(Files.isRegularFile(handler()), handler().toString());

        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertTrue(record.createdPaths().contains(mimePackage().toString()), record.createdPaths().toString());

        Uninstaller uninstaller = new Uninstaller(manifest, platform, record, Optional.empty());
        assertTrue(uninstaller.inspect().associations().contains("association:.sumap"),
                uninstaller.inspect().associations().toString());
        Uninstaller.Report report = uninstaller.run(Uninstaller.Options.SAFE, ProgressListener.NOOP,
                new CancellationToken());

        assertTrue(report.clean(), report.toString());
        assertFalse(Files.exists(mimePackage()), "the MIME package is gone");
        assertFalse(Files.exists(handler()), "the handler .desktop is gone");
        // xdg-mime may not be installed; if it wrote a default, it must have been taken back.
        Path mimeApps = home.resolve(".config/mimeapps.list");
        if (Files.exists(mimeApps)) {
            assertFalse(Files.readString(mimeApps).contains("your-product-sumap.desktop"),
                    Files.readString(mimeApps));
        }
    }

    @Test
    void deselectedAssociationsAreNotCreated() throws Exception {
        Engine.Result result = install(List.of());
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        assertFalse(Files.exists(mimePackage()), "no association was chosen");

        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertTrue(record.entries().stream().noneMatch(e -> e instanceof InstallationRecord.StepStarted s
                && s.stepId().startsWith("association:")), record.entries().toString());
    }
}
