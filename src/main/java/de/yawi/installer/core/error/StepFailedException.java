package de.yawi.installer.core.error;

/** An installation step did not complete; the engine decides about rollback. */
public class StepFailedException extends InstallerException {

    /** {@link #exitStatus()} when the step ran no process. */
    public static final int NO_EXIT_STATUS = -1;

    private final String stepId;
    private final int exitStatus;

    /**
     * @param stepId     the manifest's step id
     * @param reason     one line for the user, e.g. "exit code 3" or the file that was missing
     * @param exitStatus the process's exit status, or {@link #NO_EXIT_STATUS}
     * @param detail     captured output or the like for the detail pane, may be null
     */
    public StepFailedException(String stepId, String reason, int exitStatus, String detail, Throwable cause) {
        super(ErrorCode.STEP_FAILED, ErrorCode.STEP_FAILED.messageKey(), new Object[] {stepId, reason},
                "step '" + stepId + "' failed: " + reason
                        + (exitStatus == NO_EXIT_STATUS ? "" : " (exit status " + exitStatus + ")"),
                detail, cause);
        this.stepId = stepId;
        this.exitStatus = exitStatus;
    }

    public StepFailedException(String stepId, String reason, Throwable cause) {
        this(stepId, reason, NO_EXIT_STATUS, null, cause);
    }

    public String stepId() {
        return stepId;
    }

    public int exitStatus() {
        return exitStatus;
    }
}
