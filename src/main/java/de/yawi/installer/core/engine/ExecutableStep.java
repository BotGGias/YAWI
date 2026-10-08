package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallStep;

/**
 * A manifest step that can run. Implementations live in
 * {@code core.engine.step}; {@link StepFactory} maps the manifest's data
 * records to them. The reverse operation arrives with the rollback.
 */
public interface ExecutableStep {

    InstallStep definition();

    /**
     * Does the work. Reports through {@code context.listener()}, records
     * what it created in {@code context.record()}, honours
     * {@code context.cancellation()}.
     *
     * @throws de.yawi.installer.core.error.StepFailedException if the step failed;
     *         the engine applies {@code onFailure}
     * @throws de.yawi.installer.core.error.CancelledException on cancel
     */
    void execute(ExecutionContext context);

    /** One line for the dry run: what would happen, with placeholders resolved. */
    String describe(ExecutionContext context);
}
