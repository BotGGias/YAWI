package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Runs an {@link ExecutionPlan}: one step after the other, progress from the
 * weights, {@code onFailure} applied, every step's outcome in the
 * {@code InstallationRecord}. The rollback of a failed run is the
 * {@link InstallRunner}'s job ( {@link Rollback}); its report
 * travels in {@link Result#rollback()}.
 *
 * <p>Never throws for a failed installation - {@link Result#failure()} carries
 * the cause - so a caller always gets the step reports. Cancellation is a
 * result like any other ({@link StepOutcome#CANCELLED}).
 */
public final class Engine {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);

    /** What happened to one planned step. */
    public record StepReport(InstallStep step, StepOutcome outcome, Optional<InstallerException> failure) {
    }

    /**
     * @param failure  the error that stopped the installation, or the
     *                 cancellation; empty on success and for continued failures
     * @param rollback what the rollback after a failure did; empty on success
     *                 and when no rollback ran (a bare {@link Engine} run)
     */
    public record Result(boolean succeeded, List<StepReport> reports, Optional<InstallerException> failure,
                         Optional<Rollback.Report> rollback) {
        public Result {
            reports = List.copyOf(reports);
            rollback = rollback == null ? Optional.empty() : rollback;
        }

        public Result(boolean succeeded, List<StepReport> reports, Optional<InstallerException> failure) {
            this(succeeded, reports, failure, Optional.empty());
        }

        public Result withRollback(Rollback.Report report) {
            return new Result(succeeded, reports, failure, Optional.of(report));
        }

        public boolean isCancelled() {
            return failure.isPresent() && failure.get() instanceof CancelledException;
        }

        /** Steps that failed but were continued; the finish page names them. */
        public List<StepReport> continuedFailures() {
            return reports.stream().filter(r -> r.outcome() == StepOutcome.FAILED_CONTINUED).toList();
        }
    }

    public Result run(ExecutionPlan plan, ExecutionContext context) {
        List<StepReport> reports = new ArrayList<>();
        ProgressListener listener = context.listener();
        double done = 0;
        int count = plan.steps().size();
        listener.overall(0);
        InstallStep current = null;
        try {
            for (int i = 0; i < count; i++) {
                PlannedStep planned = plan.steps().get(i);
                InstallStep definition = planned.definition();
                current = null;
                context.cancellation().checkpoint();
                listener.stepStarted(definition, i, count);
                StepOutcome outcome;
                InstallerException failure = null;

                if (planned.isSkipped()) {
                    LOG.info("Step {} skipped: {}", definition.id(), planned.skipReason().orElseThrow());
                    outcome = StepOutcome.SKIPPED;
                } else {
                    LOG.info("Step {} [{}] starting", definition.id(), definition.typeName());
                    context.record().stepStarted(definition.id());
                    current = definition;
                    try {
                        planned.step().execute(context);
                        outcome = StepOutcome.DONE;
                        LOG.info("Step {} done", definition.id());
                    } catch (StepFailedException e) {
                        failure = e;
                        outcome = decide(definition, e, context);
                    }
                }
                context.record().stepFinished(definition.id(), outcome.name(), failure == null ? null : failure.getMessage());
                current = null;
                reports.add(new StepReport(definition, outcome, Optional.ofNullable(failure)));
                listener.stepFinished(definition, outcome);
                if (outcome == StepOutcome.FAILED) {
                    return new Result(false, reports, Optional.of(failure));
                }
                done += planned.weight();
                listener.overall(Math.min(done, 1.0));
            }
            listener.overall(1.0);
            return new Result(true, reports, Optional.empty());
        } catch (CancelledException e) {
            LOG.info("Installation cancelled");
            finishInterrupted(context, current, StepOutcome.CANCELLED, e);
            return new Result(false, reports, Optional.of(e));
        } catch (InstallerException e) {
            // Anything a step raised that is not "this step failed": space, rights, source.
            LOG.error("Installation failed [{}]: {}", e.code(), e.getMessage());
            finishInterrupted(context, current, StepOutcome.FAILED, e);
            return new Result(false, reports, Optional.of(e));
        } catch (RuntimeException e) {
            LOG.error("Installation failed unexpectedly", e);
            finishInterrupted(context, current, StepOutcome.FAILED, e);
            return new Result(false, reports, Optional.of(InstallerException.wrap(e)));
        }
    }

    /** Closes the record entry of a step that ended in something other than its own failure, so no start stays open. */
    private static void finishInterrupted(ExecutionContext context, InstallStep current, StepOutcome outcome, Exception cause) {
        if (current != null) {
            context.record().stepFinished(current.id(), outcome.name(), cause.getMessage());
        }
    }

    /**
     * Applies {@code onFailure}: abort, continue, or ask the prompt. File steps
     * always abort; a shortcut never stops an installation (E13-S01).
     */
    private static StepOutcome decide(InstallStep definition, StepFailedException failure, ExecutionContext context) {
        InstallStep.OnFailure policy = switch (definition) {
            case InstallStep.RunCommand run -> run.onFailure();
            case InstallStep.Shortcut s -> InstallStep.OnFailure.CONTINUE;
            case InstallStep.FileAssociation s -> InstallStep.OnFailure.CONTINUE;
            case InstallStep.PathEntries s -> InstallStep.OnFailure.CONTINUE;
            default -> InstallStep.OnFailure.ABORT;
        };
        StepOutcome outcome = switch (policy) {
            case ABORT -> StepOutcome.FAILED;
            case CONTINUE -> StepOutcome.FAILED_CONTINUED;
            case ASK -> context.failurePrompt().ask(failure) == FailurePrompt.Decision.CONTINUE
                    ? StepOutcome.FAILED_CONTINUED : StepOutcome.FAILED;
        };
        if (outcome == StepOutcome.FAILED_CONTINUED) {
            LOG.warn("Step {} failed but the installation continues: {}", definition.id(), failure.getMessage());
        } else {
            LOG.error("Step {} failed: {}", definition.id(), failure.getMessage());
        }
        return outcome;
    }
}
