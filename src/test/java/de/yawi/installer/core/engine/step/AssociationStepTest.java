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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E13-S02: the file-association step on this platform, with the temp folder as
 * the user's home. The files are Linux; other platforms only run the failure
 * cases here.
 */
class AssociationStepTest {

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
    }

    private ExecutionContext context() {
        Set<String> selected = manifest.resolveSelection(Set.of("core"));
        InstallationRecord record = new InstallationRecord(manifest.product().id(), manifest.product().version(),
                Instant.now(), dest, selected, Map.of());
        return new ExecutionContext(manifest, platform, dest, Map.of(), selected,
                Placeholders.of(manifest, platform, dest, Map.of(), selected), new BundledArtifacts(), record,
                capture, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
    }

    private static AssociationStep step(IntegrationConfig.FileAssociation association) {
        return new AssociationStep(new InstallStep.FileAssociation(association));
    }

    private static IntegrationConfig.FileAssociation association(String target) {
        return new IntegrationConfig.FileAssociation(".sumap", target, "YOUR INSTALLER Map", true);
    }

    @Test
    void linuxWritesTheMimePackageAndHandlerAndRecordsThem() throws Exception {
        assumeTrue(platform.os() == OperatingSystem.LINUX);
        ExecutionContext context = context();

        step(association("${destination}/bin/run.sh")).execute(context);

        Path pkg = home.resolve(".local/share/mime/packages/shortcuts-sumap.xml");
        Path handler = home.resolve(".local/share/applications/shortcuts-sumap.desktop");
        assertTrue(Files.isRegularFile(pkg), pkg.toString());
        assertTrue(Files.isExecutable(handler), "the handler .desktop must be executable");
        String pkgText = Files.readString(pkg);
        assertTrue(pkgText.contains("<glob pattern=\"*.sumap\"/>"), pkgText);
        assertTrue(pkgText.contains("application/x-vnd.shortcuts-sumap"), pkgText);
        String handlerText = Files.readString(handler);
        assertTrue(handlerText.contains("MimeType=application/x-vnd.shortcuts-sumap;\n"), handlerText);
        assertTrue(handlerText.contains("Exec=" + dest.resolve("bin/run.sh") + " %f\n"), handlerText);
        assertTrue(handlerText.contains("NoDisplay=true\n"), handlerText);

        List<String> created = context.record().createdPaths();
        assertTrue(created.contains(pkg.toString()), created.toString());
        assertTrue(created.contains(handler.toString()), created.toString());
    }

    @Test
    void aMissingTargetFailsTheStep() {
        StepFailedException e = assertThrows(StepFailedException.class,
                () -> step(association("${destination}/bin/missing")).execute(context()));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        assertEquals("association:.sumap", e.stepId());
    }

    @Test
    void aTargetOutsideTheDestinationFailsTheStep() throws Exception {
        Path outside = Files.writeString(tmp.resolve("outside.sh"), "#!/bin/sh\n");
        StepFailedException e = assertThrows(StepFailedException.class,
                () -> step(association(outside.toString())).execute(context()));
        assertTrue(e.getMessage().contains("inside the destination"), e.getMessage());
    }

    @Test
    void describeNamesTheAssociationWithoutWriting() {
        String line = step(association("${destination}/bin/run.sh")).describe(context());
        assertTrue(line.contains(".sumap"), line);
        assertTrue(line.contains("application/x-vnd.shortcuts-sumap"), line);
        assertTrue(line.endsWith(dest.resolve("bin/run.sh").toString()), line);
    }
}
