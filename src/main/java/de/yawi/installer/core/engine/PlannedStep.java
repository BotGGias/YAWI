package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallStep;

import java.util.Objects;
import java.util.Optional;

/**
 * One entry of the {@link ExecutionPlan}: the step, its normalised share of
 * the progress bar, and - if it will not run on this platform - why.
 *
 * @param weight     share of the overall progress, all entries sum to 1
 * @param skipReason present if the step is skipped before it runs
 */
public record PlannedStep(ExecutableStep step, double weight, Optional<String> skipReason) {

    public PlannedStep {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(skipReason, "skipReason");
    }

    public InstallStep definition() {
        return step.definition();
    }

    public String id() {
        return definition().id();
    }

    public boolean isSkipped() {
        return skipReason.isPresent();
    }
}
