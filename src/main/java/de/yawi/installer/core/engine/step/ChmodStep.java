package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** {@code chmod}: POSIX permissions from an octal mode; a logged no-op on Windows. */
final class ChmodStep extends AbstractFileStep {

    private static final Logger LOG = LoggerFactory.getLogger(ChmodStep.class);

    private final InstallStep.Chmod step;

    ChmodStep(InstallStep.Chmod step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        Path path = target(context, step.to());
        if (context.platform().os() == OperatingSystem.WINDOWS) {
            LOG.info("Step {}: chmod {} {} skipped on Windows", id(), step.mode(), path);
            context.listener().output("chmod: no-op on Windows");
            return;
        }
        if (!Files.exists(path)) {
            throw new StepFailedException(id(), path + " does not exist", null);
        }
        context.record().modeChanged(path, mode(Files.getPosixFilePermissions(path)));
        Files.setPosixFilePermissions(path, permissions(step.mode()));
        context.listener().stepProgress(1, path.toString());
    }

    @Override
    public String describe(ExecutionContext context) {
        return "chmod " + step.mode() + " " + context.resolve(step.to());
    }

    static Set<PosixFilePermission> permissions(String mode) {
        return PosixModes.permissions(mode);
    }

    static String mode(Set<PosixFilePermission> perms) {
        return PosixModes.mode(perms);
    }
}
