package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.ManifestException;
import de.yawi.installer.core.manifest.ManifestOrigin;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Platform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E11-S01: the plan is computed - and checkable - before anything runs. */
class ExecutionPlanTest {

    private static final Map<String, String> INPUTS = Map.of("serverPort", "50000", "autostart", "false", "mode", "lan");

    private final InstallManifest manifest = TestManifests.full();

    private ExecutionPlan plan(Platform platform, Set<String> selection) {
        Set<String> resolved = manifest.resolveSelection(selection);
        Placeholders placeholders = Placeholders.of(manifest, platform, Path.of("/opt/su"), INPUTS, resolved);
        return ExecutionPlan.build(manifest, selection, platform, placeholders, EngineTestSupport.recording(new ArrayList<>()));
    }

    private static List<String> ids(ExecutionPlan plan) {
        return plan.steps().stream().map(PlannedStep::id).toList();
    }

    @Test
    void followsTheManifestOrderAndTheSelection() {
        // The manifest's shortcut and file association are always last, in that order.
        assertEquals(List.of("unpack-core", "configure-core", "shortcut:main-shortcut", "association:.sumap",
                        "path:entries"),
                ids(plan(EngineTestSupport.linux(), Set.of("core"))));
        assertEquals(List.of("unpack-core", "configure-core", "install-server", "register-service",
                        "shortcut:main-shortcut", "association:.sumap", "path:entries"),
                ids(plan(EngineTestSupport.linux(), Set.of("server"))), "server pulls core in, each step once");
    }

    @Test
    void weightsAreNormalisedToOne() {
        ExecutionPlan plan = plan(EngineTestSupport.linux(), Set.of("server"));
        double sum = plan.steps().stream().mapToDouble(PlannedStep::weight).sum();
        assertEquals(1.0, sum, 1e-9);
        // 60 / (60 + 2 + 5 + 10 + 1 shortcut + 1 association + 1 PATH)
        assertEquals(60.0 / 80, plan.steps().get(0).weight(), 1e-9);
    }

    @Test
    void stepsWithoutCommandsForThePlatformAreSkipped() {
        // full.xml has commands for all three, chain.xml none; take a manifest step apart instead:
        ExecutionPlan linux = plan(EngineTestSupport.linux(), Set.of("server"));
        assertFalse(linux.steps().get(3).isSkipped());

        InstallManifest noWindows = TestManifests.parse("/manifest/engine/linux-only-command.xml");
        Placeholders p = Placeholders.of(noWindows, EngineTestSupport.windows(), Path.of("C:\\su"), Map.of(), Set.of("core"));
        ExecutionPlan windows = ExecutionPlan.build(noWindows, Set.of("core"), EngineTestSupport.windows(), p,
                EngineTestSupport.recording(new ArrayList<>()));
        PlannedStep run = windows.steps().get(1);
        assertTrue(run.isSkipped());
        assertEquals("no commands for windows", run.skipReason().orElseThrow());

        Placeholders pl = Placeholders.of(noWindows, EngineTestSupport.linux(), Path.of("/su"), Map.of(), Set.of("core"));
        assertFalse(ExecutionPlan.build(noWindows, Set.of("core"), EngineTestSupport.linux(), pl,
                EngineTestSupport.recording(new ArrayList<>())).steps().get(1).isSkipped());
    }

    @Test
    void missingWeightsSpreadEvenly() {
        InstallManifest m = TestManifests.parse("/manifest/engine/linux-only-command.xml");
        Placeholders p = Placeholders.of(m, EngineTestSupport.linux(), Path.of("/su"), Map.of(), Set.of("core"));
        ExecutionPlan plan = ExecutionPlan.build(m, Set.of("core"), EngineTestSupport.linux(), p,
                EngineTestSupport.recording(new ArrayList<>()));
        assertEquals(2, plan.steps().size());
        assertEquals(0.5, plan.steps().get(0).weight(), 1e-9);
        assertEquals(0.5, plan.steps().get(1).weight(), 1e-9);
    }

    /** E10-S03-T03: commands from an unsigned manifest off the network are refused at plan time. */
    @Test
    void commandStepsOfAnUntrustedManifestAreRefused() {
        InstallManifest untrusted = new ManifestParser().parse(TestManifests.bytes("/manifest/full.xml"),
                new ManifestOrigin(ManifestOrigin.Kind.EXPLICIT_URL, "https://example.invalid/installer.xml"));
        assertFalse(untrusted.origin().isTrusted());
        Placeholders p = Placeholders.of(untrusted, EngineTestSupport.linux(), Path.of("/su"), INPUTS,
                untrusted.resolveSelection(Set.of("server")));

        ManifestException e = assertThrows(ManifestException.class, () -> ExecutionPlan.build(
                untrusted, Set.of("server"), EngineTestSupport.linux(), p, EngineTestSupport.recording(new ArrayList<>())));
        assertTrue(e.getMessage().contains("not signed"), e.getMessage());
        assertEquals(List.of("step 'register-service'"), e.getProblems().stream().map(ManifestProblem::subject).toList());

        // Without the command step the same manifest plans fine ...
        assertEquals(List.of("unpack-core", "configure-core", "shortcut:main-shortcut", "association:.sumap",
                        "path:entries"),
                ids(ExecutionPlan.build(untrusted, Set.of("core"),
                EngineTestSupport.linux(), p, EngineTestSupport.recording(new ArrayList<>()))));
        // ... and a verified signature makes the command step acceptable.
        InstallManifest signed = new ManifestParser().parse(TestManifests.bytes("/manifest/full.xml"),
                new ManifestOrigin(ManifestOrigin.Kind.EXPLICIT_URL, "https://example.invalid/installer.xml",
                        ManifestOrigin.Signature.VERIFIED));
        assertTrue(ids(ExecutionPlan.build(signed, Set.of("server"), EngineTestSupport.linux(), p,
                EngineTestSupport.recording(new ArrayList<>()))).contains("register-service"));
    }

    @Test
    void unresolvablePlaceholdersAreRejectedBeforeAnythingRuns() {
        InstallManifest m = TestManifests.parse("/manifest/engine/unknown-placeholder.xml");
        Placeholders p = Placeholders.of(m, EngineTestSupport.linux(), Path.of("/su"), Map.of(), Set.of("core"));
        ManifestException e = assertThrows(ManifestException.class, () -> ExecutionPlan.build(
                m, Set.of("core"), EngineTestSupport.linux(), p, EngineTestSupport.recording(new ArrayList<>())));
        assertEquals(2, e.getProblems().size(), e.getMessage());
        assertTrue(e.getMessage().contains("step 'bad-template' - ${input.nope}"), e.getMessage());
        assertTrue(e.getMessage().contains("step 'bad-command' - ${HOME}"), e.getMessage());

        // The Windows commands are not checked on Linux: only what runs is validated.
        assertEquals(2, e.getProblems().size());
    }

    /** E13-S01: the manifest's shortcuts run as implicit steps after the component steps. */
    @Test
    void shortcutsOfTheIntegrationBlockAreAppendedAsSteps() {
        ExecutionPlan plan = plan(EngineTestSupport.linux(), Set.of("server"));
        List<String> ids = ids(plan);
        // The shortcut is third-to-last; the file association and PATH step follow it.
        assertEquals("shortcut:main-shortcut", ids.get(ids.size() - 3), ids.toString());
        PlannedStep shortcut = plan.steps().get(plan.steps().size() - 3);
        assertTrue(shortcut.definition() instanceof InstallStep.Shortcut);
        assertEquals("shortcut", shortcut.definition().typeName());
        assertFalse(shortcut.isSkipped());
        // weight 1 among 60 + 2 + 5 + 10 + 1 association + 1 PATH
        assertEquals(1.0 / 80, shortcut.weight(), 1e-9);

        // An unknown placeholder in the shortcut is rejected like in any step.
        InstallManifest bad = new ManifestParser().parse(new String(TestManifests.bytes("/manifest/full.xml"),
                java.nio.charset.StandardCharsets.UTF_8).replace("${destination}/server/su-server", "${nope}/su")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), TestManifests.origin("/manifest/full.xml"));
        Placeholders p = Placeholders.of(bad, EngineTestSupport.linux(), Path.of("/su"), INPUTS, Set.of("core"));
        ManifestException e = assertThrows(ManifestException.class, () -> ExecutionPlan.build(
                bad, Set.of("core"), EngineTestSupport.linux(), p, EngineTestSupport.recording(new ArrayList<>())));
        assertTrue(e.getMessage().contains("step 'shortcut:main-shortcut' - ${nope}"), e.getMessage());
    }

    @Test
    void coreOnlySelectionStillResolvesTheServerPortToItsDefault() {
        ExecutionPlan plan = plan(EngineTestSupport.linux(), Set.of("core"));
        assertEquals(List.of("unpack-core", "configure-core", "shortcut:main-shortcut", "association:.sumap",
                "path:entries"), ids(plan));
    }

    @Test
    void dryRunListsEveryStepWithoutExecuting() {
        List<String> ran = new ArrayList<>();
        Set<String> resolved = manifest.resolveSelection(Set.of("server"));
        Placeholders p = Placeholders.of(manifest, EngineTestSupport.linux(), Path.of("/opt/su"), INPUTS, resolved);
        ExecutionPlan plan = ExecutionPlan.build(manifest, Set.of("server"), EngineTestSupport.linux(), p,
                EngineTestSupport.recording(ran));
        ExecutionContext context = EngineTestSupport.context(manifest, EngineTestSupport.linux(), Path.of("/opt/su"),
                INPUTS, Set.of("server"), null, null, new CancellationToken());

        List<String> lines = plan.dryRun(context);

        assertEquals(List.of(
                "1. [extract] unpack-core (75 %) -> unpack-core",
                "2. [template] configure-core (3 %) -> configure-core",
                "3. [copy] install-server (6 %) -> install-server",
                "4. [run-command] register-service (13 %) -> register-service",
                "5. [shortcut] shortcut:main-shortcut (1 %) -> shortcut:main-shortcut",
                "6. [fileAssociation] association:.sumap (1 %) -> association:.sumap",
                "7. [pathEntry] path:entries (1 %) -> path:entries"), lines);
        assertEquals(List.of(), ran, "dry run executes nothing");
    }

    @Test
    void subsetKeepsTheOrderAndRenormalisesTheWeights() {
        ExecutionPlan full = plan(EngineTestSupport.linux(), Set.of("server"));
        ExecutionPlan subset = full.subset(List.of("register-service", "unpack-core", "unknown"));
        assertEquals(List.of("unpack-core", "register-service"), ids(subset), "plan order, unknown ids ignored");
        assertEquals(1.0, subset.steps().stream().mapToDouble(PlannedStep::weight).sum(), 1e-9);
        assertEquals(full.ids(), ids(full));
        assertTrue(full.subset(List.of()).isEmpty());
    }
}
