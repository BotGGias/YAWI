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
 * E13-S03 through the runner: the PATH block is added to the user's profiles on
 * install and removed - exactly, foreign lines untouched - on uninstall. Linux;
 * the home is the temp folder.
 */
class PathLifecycleTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private Path home;
    private Path dest;
    private final InstallManifest manifest = TestManifests.bundled();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(PlatformFactory.current().os() == OperatingSystem.LINUX);
        home = tmp.resolve("home");
        dest = tmp.resolve("dest");
        Files.createDirectories(home);
        // A pre-existing profile with a foreign line the installer must never touch.
        Files.writeString(home.resolve(".profile"), "export EDITOR=vim\n");
        platform = TestPlatforms.scratch(home);
    }

    private Engine.Result install(boolean addPath) {
        InstallRunner.Request request = InstallRunner.Request
                .of(manifest, platform, dest, Set.of("server"), Map.of(), Map.of(), Optional.empty())
                .withPathEntries(addPath);
        return new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    @Test
    void installAddsTheBlockAndUninstallRemovesExactlyIt() throws Exception {
        Engine.Result result = install(true);
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));

        Path profile = home.resolve(".profile");
        String afterInstall = Files.readString(profile);
        assertTrue(afterInstall.contains("export EDITOR=vim"), afterInstall);
        assertTrue(afterInstall.contains("export PATH=\"" + dest.resolve("bin") + ":$PATH\""), afterInstall);

        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        Uninstaller uninstaller = new Uninstaller(manifest, platform, record, Optional.empty());
        assertTrue(uninstaller.inspect().pathEntries().contains("path:entries"),
                uninstaller.inspect().pathEntries().toString());

        Uninstaller.Report report = uninstaller.run(Uninstaller.Options.SAFE, ProgressListener.NOOP,
                new CancellationToken());
        assertTrue(report.clean(), report.toString());

        String afterUninstall = Files.readString(profile);
        assertTrue(afterUninstall.contains("export EDITOR=vim"), "the foreign line stays: " + afterUninstall);
        assertFalse(afterUninstall.contains("export PATH="), "the block is gone: " + afterUninstall);
    }

    @Test
    void withoutTheOptionNoBlockIsAdded() throws Exception {
        Engine.Result result = install(false);
        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
        assertFalse(Files.readString(home.resolve(".profile")).contains("export PATH="), "no PATH block");
    }
}
