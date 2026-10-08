package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E13-S03: the PATH step on this platform, with the temp folder as the user's
 * home. The profile files are Linux/macOS; the failure cases run anywhere.
 */
class PathStepTest {

    @TempDir
    Path tmp;

    private Path home;
    private Path dest;
    private Platform platform;
    private InstallManifest manifest;
    private StepTestSupport.Capture capture;

    @BeforeEach
    void setUp() throws IOException {
        home = tmp.resolve("home");
        dest = tmp.resolve("dest");
        Files.createDirectories(home);
        platform = TestPlatforms.scratch(home);
        manifest = TestManifests.parse("/manifest/engine/shortcut-update.xml"); // product id "shortcuts"
        capture = new StepTestSupport.Capture();
    }

    private ExecutionContext context() {
        Set<String> selected = manifest.resolveSelection(Set.of("core"));
        InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                Instant.now(), dest, selected, Map.of());
        return new ExecutionContext(manifest, platform, dest, Map.of(), selected,
                Placeholders.of(manifest, platform, dest, Map.of(), selected), new BundledArtifacts(), record,
                capture, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    private static PathStep step(String... entries) {
        return new PathStep(new InstallStep.PathEntries(List.of(entries)));
    }

    @Test
    void linuxCreatesTheProfileWithABlockButLeavesZshrcAloneWhenAbsent() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX || platform.os() == OperatingSystem.MACOS);
        step("${destination}/bin").execute(context());

        Path profile = home.resolve(".profile");
        assertTrue(Files.isRegularFile(profile), profile.toString());
        String text = Files.readString(profile);
        assertTrue(text.contains("export PATH=\"" + dest.resolve("bin") + ":$PATH\""), text);
        assertFalse(Files.exists(home.resolve(".zshrc")), ".zshrc is not created when it does not exist");
    }

    @Test
    void linuxAppendsToAnExistingZshrcWithoutDuplicating() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX || platform.os() == OperatingSystem.MACOS);
        Files.writeString(home.resolve(".zshrc"), "# my zsh\n");
        step("${destination}/bin").execute(context());
        step("${destination}/bin").execute(context()); // run again: dedup

        String zshrc = Files.readString(home.resolve(".zshrc"));
        assertTrue(zshrc.startsWith("# my zsh\n"), zshrc);
        assertEquals(1, countOccurrences(zshrc, "export PATH="), zshrc);
    }

    @Test
    void perUserProfilesAreNotRecordedAsCreatedFiles() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX || platform.os() == OperatingSystem.MACOS);
        ExecutionContext context = context();
        step("${destination}/bin").execute(context);
        assertFalse(context.record().createdPaths().contains(home.resolve(".profile").toString()),
                "the user's profile is appended to, never owned");
    }

    @Test
    void anEntryOutsideTheDestinationFailsTheStep() {
        StepFailedException e = assertThrows(StepFailedException.class,
                () -> step("/etc").execute(context()));
        assertTrue(e.getMessage().contains("inside the destination"), e.getMessage());
        assertEquals("path:entries", e.stepId());
    }

    @Test
    void describeNamesTheDirectoriesWithoutWriting() {
        String line = step("${destination}/bin").describe(context());
        assertTrue(line.contains(dest.resolve("bin").toString()), line);
        assertFalse(Files.exists(home.resolve(".profile")), "describe writes nothing");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
