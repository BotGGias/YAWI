package de.yawi.installer.core.engine;

/** How a planned step ended. */
public enum StepOutcome {
    /** Ran to completion. */
    DONE,
    /** Not run: no commands for this platform, or nothing to do. */
    SKIPPED,
    /** Failed, but {@code onFailure="continue"} (or the user) let the installation go on. */
    FAILED_CONTINUED,
    /** Failed and stopped the installation. */
    FAILED,
    /** Interrupted by the user. */
    CANCELLED
}
