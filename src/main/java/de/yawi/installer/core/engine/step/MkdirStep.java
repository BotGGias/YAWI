package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.manifest.InstallStep;

import java.io.IOException;
import java.nio.file.Path;

/** {@code mkdir}: the directory and any missing parents. */
final class MkdirStep extends AbstractFileStep {

    private final InstallStep.Mkdir step;

    MkdirStep(InstallStep.Mkdir step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        Path dir = target(context, step.to());
        context.listener().stepProgress(-1, dir.toString());
        createDirectories(context, dir);
        context.listener().stepProgress(1, dir.toString());
    }

    @Override
    public String describe(ExecutionContext context) {
        return "mkdir " + context.resolve(step.to());
    }
}
