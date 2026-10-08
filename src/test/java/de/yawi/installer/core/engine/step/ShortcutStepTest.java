package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.IntegrationConfig;
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
 * E13-S01: the shortcut step on this platform, with the temp folder as the
 * user's home. The file contents are Linux; Windows and macOS only run the
 * failure cases here.
 */
class ShortcutStepTest {

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
        platform = TestPlatforms.scratch(home);
        manifest = TestManifests.parse("/manifest/engine/shortcut-update.xml");
        capture = new StepTestSupport.Capture();
        Files.createDirectories(dest.resolve("bin"));
        Files.writeString(dest.resolve("bin/run.sh"), "#!/bin/sh\n");
        Files.writeString(dest.resolve("README.txt"), "hi");
    }

    private ExecutionContext context() {
        Set<String> selected = manifest.resolveSelection(Set.of("core"));
        InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                Instant.now(), dest, selected, Map.of());
        return new ExecutionContext(manifest, platform, dest, Map.of(), selected,
                Placeholders.of(manifest, platform, dest, Map.of(), selected), new BundledArtifacts(), record,
                capture, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    private static ShortcutStep step(IntegrationConfig.Shortcut shortcut) {
        return new ShortcutStep(new InstallStep.Shortcut(shortcut));
    }

    private IntegrationConfig.Shortcut shortcut(String id) {
        return manifest.integration().shortcut(id).orElseThrow();
    }

    private Path menuFile(String base) {
        return home.resolve(".local/share/applications/" + base + ".desktop");
    }

    @Test
    void linuxWritesMenuAndDesktopEntriesWithTheProductIconAndRecordsThem() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX);
        ExecutionContext context = context();

        step(shortcut("run")).execute(context);

        Path menu = menuFile("shortcuts-run");
        Path desktop = home.resolve("Desktop/shortcuts-run.desktop");
        Path icon = home.resolve(".local/share/icons/hicolor/256x256/apps/shortcuts-run.png");
        assertTrue(Files.isRegularFile(menu));
        assertTrue(Files.isExecutable(desktop), "the desktop launcher must be executable");
        assertTrue(Files.isRegularFile(icon), "product icon copied, the shortcut has none");
        String entry = Files.readString(menu);
        assertEquals(entry, Files.readString(desktop));
        assertTrue(entry.contains("Name=Sample Run\n"), entry);
        assertTrue(entry.contains("Exec=" + dest.resolve("bin/run.sh") + "\n"), entry);
        assertTrue(entry.contains("Path=" + dest + "\n"), entry);
        assertTrue(entry.contains("Icon=shortcuts-run\n"), entry);

        List<String> created = context.record().createdPaths();
        assertTrue(created.contains(menu.toString()), created.toString());
        assertTrue(created.contains(desktop.toString()), created.toString());
        assertTrue(created.contains(icon.toString()), created.toString());
        // The applications folder did not exist: recorded as created, so an uninstall removes it if empty.
        assertTrue(context.record().entries().contains(
                new InstallationRecord.CreatedDirectory(home.resolve(".local/share/applications").toString())),
                context.record().entries().toString());
    }

    @Test
    void desktopFalseAndANonPngIconGiveAMenuEntryWithoutIcon() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX);
        ExecutionContext context = context();

        step(shortcut("menu-only")).execute(context);

        String entry = Files.readString(menuFile("shortcuts-menu-only"));
        assertFalse(Files.exists(home.resolve("Desktop/shortcuts-menu-only.desktop")));
        assertFalse(entry.contains("Icon="), entry);
        assertTrue(capture.output.stream().anyMatch(l -> l.contains("is not a PNG")), capture.output.toString());
    }

    @Test
    void anExistingEntryIsBackedUpAndRecordedAsReplaced() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX);
        Path menu = menuFile("shortcuts-run");
        Files.createDirectories(menu.getParent());
        Files.writeString(menu, "[Desktop Entry]\nName=Old\n");
        ExecutionContext context = context();

        step(shortcut("run")).execute(context);

        assertTrue(Files.readString(menu).contains("Name=Sample Run"));
        InstallationRecord.ReplacedFile replaced = context.record().entries().stream()
                .filter(InstallationRecord.ReplacedFile.class::isInstance).map(InstallationRecord.ReplacedFile.class::cast)
                .filter(r -> r.path().equals(menu.toString())).findFirst().orElseThrow();
        Path saved = context.record().resolve(replaced.backup());
        assertTrue(saved.startsWith(dest.resolve(".installer/backup")), saved.toString());
        assertEquals("[Desktop Entry]\nName=Old\n", Files.readString(saved));
        assertFalse(context.record().createdPaths().contains(menu.toString()), "replaced, not created");
    }

    @Test
    void aMissingTargetFailsTheStep() {
        ExecutionContext context = context();
        IntegrationConfig.Shortcut gone = new IntegrationConfig.Shortcut("gone", true, true, "Gone",
                "${destination}/bin/missing", null);
        StepFailedException e = assertThrows(StepFailedException.class, () -> step(gone).execute(context));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertEquals("shortcut:gone", e.stepId());
    }

    @Test
    void aTargetOutsideTheDestinationFailsTheStep() throws Exception {
        Path outside = Files.writeString(tmp.resolve("outside.sh"), "#!/bin/sh\n");
        ExecutionContext context = context();
        IntegrationConfig.Shortcut escape = new IntegrationConfig.Shortcut("escape", true, true, "Escape",
                outside.toString(), null);
        StepFailedException e = assertThrows(StepFailedException.class, () -> step(escape).execute(context));
        assertTrue(e.getMessage().contains("inside the destination"), e.getMessage());
    }

    @Test
    void describeNamesTheFilesWithoutWriting() {
        String line = step(shortcut("run")).describe(context());
        assertTrue(line.startsWith("shortcut \"Sample Run\" -> " + dest.resolve("bin/run.sh")), line);
        assertTrue(line.contains(platform.desktopDir().toString()), line);
        assertFalse(Files.exists(platform.desktopDir()));
    }
}
