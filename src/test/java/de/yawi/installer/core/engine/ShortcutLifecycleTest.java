package de.yawi.installer.core.engine;

import de.yawi.installer.core.download.HttpDownloader;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E13-S01 through the runner: shortcuts are created with the installation,
 * put back by the rollback of a failed update, removed by the uninstaller.
 * Linux (.desktop files); the home is the temp folder.
 */
class ShortcutLifecycleTest {

    @TempDir
    Path tmp;

    private Platform platform;
    private Path home;
    private Path dest;
    private final InstallManifest manifest = TestManifests.parse("/manifest/engine/shortcut-update.xml");

    @BeforeEach
    void setUp() {
        assumeTrue(de.yawi.installer.core.platform.PlatformFactory.current().os() == OperatingSystem.LINUX);
        home = tmp.resolve("home");
        dest = tmp.resolve("dest");
        platform = TestPlatforms.scratch(home);
    }

    private Engine.Result run(Set<String> selection) {
        InstallRunner.Request request = InstallRunner.Request.of(manifest, platform, dest, selection,
                Map.of(), Map.of(), Optional.empty());
        return new InstallRunner(new SourceResolver(new HttpDownloader()))
                .run(request, ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    private Path menu(String base) {
        return home.resolve(".local/share/applications/" + base + ".desktop");
    }

    @Test
    void installUpdateRollbackAndUninstall() throws Exception {
        // 1. A fresh installation: two menu entries, one desktop entry, one icon.
        Engine.Result first = run(Set.of("core"));
        assertTrue(first.succeeded(), first.failure().map(Throwable::getMessage).orElse(""));
        assertTrue(first.reports().stream().allMatch(r -> r.outcome() == StepOutcome.DONE), first.reports().toString());
        Path run = menu("shortcuts-run");
        Path readme = menu("shortcuts-menu-only");
        Path desktop = home.resolve("Desktop/shortcuts-run.desktop");
        Path icon = home.resolve(".local/share/icons/hicolor/256x256/apps/shortcuts-run.png");
        for (Path p : List.of(run, readme, desktop, icon)) {
            assertTrue(Files.isRegularFile(p), p.toString());
        }
        InstallationRecord record = RecordReader.read(RecordWriter.defaultFile(dest));
        assertTrue(record.createdPaths().contains(run.toString()), record.createdPaths().toString());
        assertEquals(List.of("shortcut:run", "shortcut:menu-only"), record.entries().stream()
                .filter(InstallationRecord.StepFinished.class::isInstance)
                .map(e -> ((InstallationRecord.StepFinished) e).stepId()).filter(id -> id.startsWith("shortcut:")).toList());

        // 2. A later run replacing the entry backs it up; its rollback puts the previous entry back.
        Files.writeString(run, Files.readString(run) + "X-Marker=kept\n");
        ExecutionContext context = EngineTestSupport.context(manifest, platform, dest, Map.of(), Set.of("core"),
                ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        ExecutionPlan plan = ExecutionPlan.build(manifest, Set.of("core"), platform, context.placeholders(), Steps.DEFAULT);
        Steps.DEFAULT.create(new InstallStep.Shortcut(manifest.integration().shortcut("run").orElseThrow()))
                .execute(context);
        assertFalse(Files.readString(run).contains("X-Marker"), "rewritten");
        Path saved = context.record().resolve(context.record().entries().stream()
                .filter(InstallationRecord.ReplacedFile.class::isInstance).map(InstallationRecord.ReplacedFile.class::cast)
                .filter(r -> r.path().equals(run.toString())).findFirst().orElseThrow().backup());
        assertTrue(saved.startsWith(dest.resolve(".installer/backup")), saved.toString());

        Rollback.Report rolledBack = new Rollback(context, plan, Backup.of(context), Optional.empty(), false).run();
        assertTrue(rolledBack.clean(), rolledBack.toString());
        assertTrue(Files.readString(run).endsWith("X-Marker=kept\n"), "restored from the backup");
        assertTrue(Files.isRegularFile(desktop));
        assertTrue(Files.isRegularFile(icon));
        assertFalse(Files.exists(dest.resolve(".installer/backup")), "backup folder pruned");

        // 3. The uninstaller removes everything the record names, outside the destination too.
        InstallationRecord before = RecordReader.read(RecordWriter.defaultFile(dest));
        Uninstaller uninstaller = new Uninstaller(manifest, platform, before, Optional.empty());
        Uninstaller.Inventory inventory = uninstaller.inspect();
        assertTrue(inventory.files().contains(run.toString()), inventory.files().toString());
        // deleteModified: the marker was written after the run finished, that must not keep the entry here.
        Uninstaller.Report report = uninstaller.run(new Uninstaller.Options(true, false), ProgressListener.NOOP,
                new CancellationToken());
        assertTrue(report.clean(), report.toString());
        for (Path p : List.of(run, readme, desktop, icon)) {
            assertFalse(Files.exists(p), p.toString());
        }
        // The applications folder was recorded as created; it is gone unless update-desktop-database left its cache.
        Path applications = home.resolve(".local/share/applications");
        if (Files.exists(applications)) {
            try (var files = Files.list(applications)) {
                assertEquals(List.of("mimeinfo.cache"), files.map(p -> p.getFileName().toString()).toList());
            }
        }
        assertFalse(Files.exists(dest));
    }
}
