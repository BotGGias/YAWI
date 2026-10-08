package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.engine.ExecutionPlan;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.BundledArtifacts;
import de.yawi.installer.core.engine.Placeholders;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevationPlannerTest {

    private static final InstallManifest MANIFEST = TestManifests.parse("/manifest/engine/elevated.xml");
    private static final Path DEST = Path.of("/opt/elev");

    private static Platform linux() {
        return PlatformFactory.detect("Linux", "amd64", Environment.of(Map.of("HOME", "/home/me"),
                Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp")));
    }

    private static ExecutionPlan plan(Set<String> selection) {
        Set<String> resolved = MANIFEST.resolveSelection(selection);
        Placeholders p = Placeholders.of(MANIFEST, linux(), DEST, Map.of("adminPassword", "hunter2"), resolved);
        return ExecutionPlan.build(MANIFEST, resolved, linux(), p, Steps.DEFAULT);
    }

    @Test
    void elevatedCommandStepsMakeAPartialRun() {
        ElevationNeed need = ElevationPlanner.assess(plan(Set.of("core")), false, false);
        assertEquals(ElevationNeed.Mode.PARTIAL, need.mode());
        assertEquals(List.of("service"), need.elevatedStepIds());
        assertFalse(need.destinationRequires());
        assertEquals(List.of("service", "boom"),
                ElevationPlanner.assess(plan(Set.of("core", "broken")), false, false).elevatedStepIds());
    }

    @Test
    void aDestinationThatNeedsRightsMakesTheWholeRunElevated() {
        ElevationNeed need = ElevationPlanner.assess(plan(Set.of("core")), true, false);
        assertEquals(ElevationNeed.Mode.WHOLE, need.mode());
        assertEquals(List.of("mk", "write", "service"), need.elevatedStepIds());
        assertTrue(need.destinationRequires());
    }

    @Test
    void anElevatedProcessNeedsNothingAndSoDoesAPlanWithoutElevatedSteps() {
        assertEquals(ElevationNeed.NONE, ElevationPlanner.assess(plan(Set.of("core")), true, true));
        ExecutionPlan plain = plan(Set.of("core")).subset(List.of("mk", "write"));
        assertEquals(ElevationNeed.NONE, ElevationPlanner.assess(plain, false, false));
        assertFalse(ElevationNeed.NONE.required());
    }

    @Test
    void commandsAreListedInClearTextWithSecretsMasked() {
        Map<String, String> inputs = Map.of("adminPassword", "hunter2");
        Map<String, String> masked = ElevationPlanner.maskSecrets(MANIFEST, inputs);
        assertEquals(Map.of("adminPassword", ElevationPlanner.MASK), masked);

        Set<String> selected = MANIFEST.resolveSelection(Set.of("core"));
        Placeholders p = Placeholders.of(MANIFEST, linux(), DEST, masked, selected);
        ExecutionPlan plan = ExecutionPlan.build(MANIFEST, selected, linux(), p, Steps.DEFAULT);
        ExecutionContext context = new ExecutionContext(MANIFEST, linux(), DEST, masked, selected, p,
                new BundledArtifacts(), new InstallationRecord("elev", "1", Instant.EPOCH, DEST, selected, Map.of()),
                ProgressListener.NOOP, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        List<String> lines = ElevationPlanner.describeCommands(plan, List.of("mk", "write", "service"), context);
        assertEquals(2, lines.size(), "only command steps are listed: " + lines);
        assertTrue(lines.get(0).startsWith("write: "), lines.get(0));
        assertTrue(lines.get(1).startsWith("service: "), lines.get(1));
        assertTrue(lines.stream().noneMatch(l -> l.contains("hunter2")), "secret must not show: " + lines);
    }
}
