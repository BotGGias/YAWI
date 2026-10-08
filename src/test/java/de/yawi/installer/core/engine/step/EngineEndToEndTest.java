package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.TestPlatforms;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.RecordReader;
import de.yawi.installer.core.state.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 2 end to end, headless: the bundled manifest with its demo
 * payload installs completely into a temp folder - extract, template, copy,
 * chmod and the platform's run-command - and leaves a readable record.
 */
class EngineEndToEndTest {

    @TempDir
    Path tmp;

    @Test
    void bundledManifestInstallsCompletely() throws Exception {
        InstallManifest manifest = TestManifests.bundled();
        Platform platform = TestPlatforms.scratch(tmp.resolve("home"));
        Path dest = tmp.resolve("YOUR INSTALLER");
        Set<String> selected = manifest.resolveSelection(Set.of("core", "server"));
        Map<String, String> inputs = Map.of("serverPort", "51234");
        InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                Instant.now(), dest, selected, inputs);
        StepTestSupport.Capture capture = new StepTestSupport.Capture();
        ExecutionContext context = new ExecutionContext(manifest, platform, dest, inputs, selected,
                Placeholders.of(manifest, platform, dest, inputs, selected), new BundledArtifacts(), record,
                capture, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        ExecutionPlan plan = ExecutionPlan.build(manifest, selected, platform, context.placeholders(), Steps.DEFAULT);

        List<String> dryRun = plan.dryRun(context);
        assertEquals(8, dryRun.size(), dryRun.toString()); // five manifest steps + shortcut + association + PATH

        Engine.Result result;
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(dest), record)) {
            result = new Engine().run(plan, context);
        }

        assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse("") + " " + capture.output);
        assertTrue(result.reports().stream().allMatch(r -> r.outcome() == StepOutcome.DONE), result.reports().toString());
        assertTrue(Files.isRegularFile(dest.resolve("data/maps/green-valley.map")));
        String settings = Files.readString(dest.resolve("settings.ini"));
        assertTrue(settings.contains("installDir=" + dest), settings);
        assertTrue(settings.contains("port=51234"), settings);
        assertTrue(Files.isRegularFile(dest.resolve("bin/su-server")));
        if (platform.os() != de.yawi.installer.core.platform.OperatingSystem.WINDOWS) {
            assertTrue(Files.isExecutable(dest.resolve("bin/su-server")), "chmod step");
            assertTrue(capture.output.contains("registering su-server in " + dest), capture.output.toString());
        }

        if (platform.os() == de.yawi.installer.core.platform.OperatingSystem.LINUX) {
            // The manifest's shortcut, in the scratch home.
            Path home = tmp.resolve("home");
            Path menuEntry = home.resolve(".local/share/applications/your-product-main-shortcut.desktop");
            assertTrue(Files.isRegularFile(menuEntry), menuEntry.toString());
            assertTrue(Files.isExecutable(home.resolve("Desktop/your-product-main-shortcut.desktop")));
            assertTrue(Files.isRegularFile(home.resolve(
                    ".local/share/icons/hicolor/256x256/apps/your-product-main-shortcut.png")));
            String entry = Files.readString(menuEntry);
            assertTrue(entry.contains("Exec=" + dest.resolve("server/su-server")), entry);
            assertTrue(entry.contains("Icon=your-product-main-shortcut"), entry);
        }

        InstallationRecord read = RecordReader.read(RecordWriter.defaultFile(dest));
        assertEquals(List.of("core", "server"), read.components().stream().sorted().toList());
        assertEquals("51234", read.inputs().get("serverPort"));
        assertTrue(read.createdPaths().contains("settings.ini"), read.createdPaths().toString());
        assertTrue(read.createdPaths().contains("bin/su-server"));
        assertTrue(read.entries().contains(new InstallationRecord.StepFinished("shortcut:main-shortcut", "DONE", null)),
                read.entries().toString());
        assertTrue(read.entries().contains(new InstallationRecord.StepFinished("register-service", "DONE", null)),
                read.entries().toString());
    }
}
