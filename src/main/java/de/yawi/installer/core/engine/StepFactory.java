package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallStep;

/** Maps a manifest step to its executable form; the default one is {@code core.engine.step.Steps}. */
@FunctionalInterface
public interface StepFactory {

    ExecutableStep create(InstallStep definition);
}
