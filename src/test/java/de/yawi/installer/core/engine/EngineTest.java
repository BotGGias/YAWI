package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.InsufficientSpaceException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The engine loop with stub steps: order, progress, onFailure, cancel, record. */
class EngineTest {

    private static final Map<String, String> INPUTS = Map.of("serverPort", "50000", "autostart", "false", "mode", "lan");
    private final InstallManifest manifest = TestManifests.full();

    /** Collects every listener call as text. */
    private static final class Log implements ProgressListener {
        final List<String> events = new ArrayList<>();
        final List<Double> overall = new ArrayList<>();

        @Override
        public void stepStarted(InstallStep step, int index, int count) {
            events.add("start " + step.id() + " " + (index + 1) + "/" + count);
        }

        @Override
        public void stepFinished(InstallStep step, StepOutcome outcome) {
            events.add("end " + step.id() + " " + outcome);
        }

        @Override
        public void overall(double fraction) {
            overall.add(fraction);
        }
    }

    private ExecutionContext context(Log log, FailurePrompt prompt, CancellationToken token) {
        return EngineTestSupport.context(manifest, EngineTestSupport.linux(), Path.of("/opt/su"), INPUTS,
                Set.of("server"), log, prompt, token);
    }

    private ExecutionPlan plan(ExecutionContext context, StepFactory factory) {
        return ExecutionPlan.build(manifest, Set.of("server"), context.platform(), context.placeholders(), factory);
    }

    @Test
    void runsEveryStepInOrderAndReportsMonotonicProgress() {
        Log log = new Log();
        List<String> ran = new ArrayList<>();
        ExecutionContext context = context(log, null, new CancellationToken());

        Engine.Result result = new Engine().run(plan(context, EngineTestSupport.recording(ran)), context);

        assertTrue(result.succeeded());
        assertEquals(List.of("unpack-core", "configure-core", "install-server", "register-service",
                "shortcut:main-shortcut", "association:.sumap", "path:entries"), ran);
        assertEquals("start unpack-core 1/7", log.events.get(0));
        assertEquals("end path:entries DONE", log.events.get(log.events.size() - 1));
        for (int i = 1; i < log.overall.size(); i++) {
            assertTrue(log.overall.get(i) >= log.overall.get(i - 1), log.overall.toString());
        }
        assertEquals(1.0, log.overall.get(log.overall.size() - 1));
        assertEquals(7, result.reports().size());
        assertTrue(result.reports().stream().allMatch(r -> r.outcome() == StepOutcome.DONE));
    }

    @Test
    void abortStopsAtTheFailedStepAndRecordsIt() {
        Log log = new Log();
        List<String> ran = new ArrayList<>();
        ExecutionContext context = context(log, null, new CancellationToken());
        StepFactory factory = definition -> EngineTestSupport.step(definition, ctx -> {
            ran.add(definition.id());
            if (definition.id().equals("configure-core")) {
                throw new StepFailedException(definition.id(), "template missing", null);
            }
        });

        Engine.Result result = new Engine().run(plan(context, factory), context);

        assertFalse(result.succeeded());
        assertEquals(List.of("unpack-core", "configure-core"), ran, "nothing after the failure");
        assertInstanceOf(StepFailedException.class, result.failure().orElseThrow());
        assertEquals(StepOutcome.FAILED, result.reports().get(1).outcome());
        assertEquals("end configure-core FAILED", log.events.get(log.events.size() - 1));

        List<InstallationRecord.Entry> entries = context.record().entries();
        assertTrue(entries.contains(new InstallationRecord.StepStarted("configure-core")));
        assertTrue(entries.contains(new InstallationRecord.StepFinished("configure-core", "FAILED",
                "step 'configure-core' failed: template missing")), entries.toString());
        assertTrue(entries.contains(new InstallationRecord.StepFinished("unpack-core", "DONE", null)));
    }

    @Test
    void continueAndAskFollowTheManifestAndThePrompt() {
        // register-service in full.xml is onFailure="abort"; use a manifest variant for continue/ask.
        InstallManifest m = TestManifests.parse("/manifest/engine/on-failure.xml");
        List<StepFailedException> asked = new ArrayList<>();
        FailurePrompt prompt = failure -> {
            asked.add(failure);
            return FailurePrompt.Decision.CONTINUE;
        };
        ExecutionContext context = EngineTestSupport.context(m, EngineTestSupport.linux(), Path.of("/su"), Map.of(),
                Set.of("core"), null, prompt, new CancellationToken());
        StepFactory failing = definition -> EngineTestSupport.step(definition, ctx -> {
            throw new StepFailedException(definition.id(), "boom", 3, "output", null);
        });
        ExecutionPlan plan = ExecutionPlan.build(m, Set.of("core"), context.platform(), context.placeholders(), failing);

        Engine.Result result = new Engine().run(plan, context);

        assertEquals(List.of("continue-me", "ask-me", "abort-me"),
                result.reports().stream().map(r -> r.step().id()).toList());
        assertEquals(StepOutcome.FAILED_CONTINUED, result.reports().get(0).outcome());
        assertEquals(StepOutcome.FAILED_CONTINUED, result.reports().get(1).outcome());
        assertEquals(StepOutcome.FAILED, result.reports().get(2).outcome());
        assertEquals(1, asked.size());
        assertEquals("ask-me", asked.get(0).stepId());
        assertFalse(result.succeeded());
        assertEquals(2, result.continuedFailures().size());

        // The prompt says abort: the ask step ends the run.
        ExecutionContext abortCtx = EngineTestSupport.context(m, EngineTestSupport.linux(), Path.of("/su"), Map.of(),
                Set.of("core"), null, FailurePrompt.ABORT_ALWAYS, new CancellationToken());
        Engine.Result aborted = new Engine().run(
                ExecutionPlan.build(m, Set.of("core"), abortCtx.platform(), abortCtx.placeholders(), failing), abortCtx);
        assertEquals(2, aborted.reports().size());
        assertEquals(StepOutcome.FAILED, aborted.reports().get(1).outcome());
    }

    /** E13-S01: a shortcut that cannot be placed is reported, the installation still succeeds. */
    @Test
    void aFailingShortcutStepNeverAbortsTheRun() {
        Log log = new Log();
        List<String> ran = new ArrayList<>();
        ExecutionContext context = context(log, null, new CancellationToken());
        StepFactory factory = definition -> EngineTestSupport.step(definition, ctx -> {
            ran.add(definition.id());
            if (definition instanceof InstallStep.Shortcut) {
                throw new StepFailedException(definition.id(), "no menu here", null);
            }
        });

        Engine.Result result = new Engine().run(plan(context, factory), context);

        assertTrue(result.succeeded());
        // The association and PATH step run after the shortcut, so it is third-to-last.
        assertEquals("shortcut:main-shortcut", ran.get(ran.size() - 3));
        assertEquals(List.of("shortcut:main-shortcut"),
                result.continuedFailures().stream().map(r -> r.step().id()).toList());
        assertTrue(log.events.contains("end shortcut:main-shortcut FAILED_CONTINUED"), log.events.toString());
    }

    @Test
    void cancelBetweenStepsEndsTheRunWithCancelled() {
        CancellationToken token = new CancellationToken();
        List<String> ran = new ArrayList<>();
        ExecutionContext context = context(new Log(), null, token);
        StepFactory factory = definition -> EngineTestSupport.step(definition, ctx -> {
            ran.add(definition.id());
            if (definition.id().equals("unpack-core")) {
                token.cancel(); // the user hits Cancel while step 1 runs
            }
        });

        Engine.Result result = new Engine().run(plan(context, factory), context);

        assertFalse(result.succeeded());
        assertTrue(result.isCancelled());
        assertEquals(List.of("unpack-core"), ran);
        assertInstanceOf(CancelledException.class, result.failure().orElseThrow());
    }

    @Test
    void cancelInsideAStepClosesItsRecordEntry() {
        CancellationToken token = new CancellationToken();
        ExecutionContext context = context(new Log(), null, token);
        StepFactory factory = definition -> EngineTestSupport.step(definition, ctx -> {
            token.cancel();
            token.checkpoint(); // the step notices the cancellation itself
        });

        Engine.Result result = new Engine().run(plan(context, factory), context);

        assertTrue(result.isCancelled());
        // The record closes the interrupted step, so the rollback sees it as started and finished.
        assertTrue(context.record().entries().contains(new InstallationRecord.StepFinished("unpack-core", "CANCELLED",
                result.failure().orElseThrow().getMessage())), context.record().entries().toString());
    }

    @Test
    void otherInstallerErrorsEndTheRunWithTheirCause() {
        ExecutionContext context = context(new Log(), null, new CancellationToken());
        StepFactory factory = definition -> EngineTestSupport.step(definition, ctx -> {
            throw new InsufficientSpaceException(Path.of("/opt/su"), 10, 1);
        });
        Engine.Result result = new Engine().run(plan(context, factory), context);
        assertInstanceOf(InsufficientSpaceException.class, result.failure().orElseThrow());
        assertEquals(0, result.reports().size());
        assertTrue(context.record().entries().stream().anyMatch(e -> e instanceof InstallationRecord.StepFinished f
                && f.outcome().equals("FAILED")), context.record().entries().toString());
        assertTrue(result.rollback().isEmpty(), "a bare engine run does not roll back");
    }

    @Test
    void skippedStepsCountTowardsProgressWithoutRunning() {
        InstallManifest m = TestManifests.parse("/manifest/engine/linux-only-command.xml");
        List<String> ran = new ArrayList<>();
        Log log = new Log();
        ExecutionContext context = EngineTestSupport.context(m, EngineTestSupport.windows(), Path.of("C:\\su"), Map.of(),
                Set.of("core"), log, null, new CancellationToken());
        ExecutionPlan plan = ExecutionPlan.build(m, Set.of("core"), context.platform(), context.placeholders(),
                EngineTestSupport.recording(ran));

        Engine.Result result = new Engine().run(plan, context);

        assertTrue(result.succeeded());
        assertEquals(List.of("make-dir"), ran);
        assertEquals(StepOutcome.SKIPPED, result.reports().get(1).outcome());
        assertEquals(1.0, log.overall.get(log.overall.size() - 1));
    }
}
