package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.StepFailedException;

/**
 * Asked when a step with {@code onFailure="ask"} fails. The UI shows a
 * dialog; the silent mode answers {@link #ABORT_ALWAYS}.
 */
@FunctionalInterface
public interface FailurePrompt {

    enum Decision { CONTINUE, ABORT }

    FailurePrompt ABORT_ALWAYS = failure -> Decision.ABORT;

    Decision ask(StepFailedException failure);
}
