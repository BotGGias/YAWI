package de.yawi.installer.core.error;

/**
 * The catalogue of failure causes: one place that ties a cause to its exit
 * code and to the bundle keys of the user's text and the hint what
 * to do about it.
 *
 * <p>Exit code {@code 0} is success and has no entry here. The codes are part
 * of the silent mode's contract; never renumber, only append.
 */
public enum ErrorCode {

    /** Anything without a more specific cause; the log has the details. */
    GENERAL(1, "error.general"),
    /** Command line arguments the installer does not understand. */
    INVALID_ARGUMENTS(2, "error.arguments"),
    /** Manifest not found, unreadable or rejected by schema or validator. */
    MANIFEST_INVALID(3, "error.manifest"),
    /** Checksum or signature of a downloaded file does not match. */
    INTEGRITY_FAILED(4, "error.integrity"),
    /** Writing somewhere the process may not write. */
    PERMISSION_DENIED(5, "error.permission"),
    /** The target file system ran out of space. */
    NOT_ENOUGH_SPACE(6, "error.space"),
    /** The user cancelled - not a defect, but the run did not complete . */
    CANCELLED(7, "error.cancelled"),
    /** No provider of a source could deliver. */
    SOURCE_UNAVAILABLE(8, "error.source"),
    /** An installation step failed . */
    STEP_FAILED(9, "error.step"),
    /** Unknown operating system or architecture . */
    UNSUPPORTED_PLATFORM(10, "error.platform"),
    /** {@code --update} found no installation record under {@code --dest} (update contract). */
    NO_INSTALLATION(11, "error.noInstallation"),
    /** A process named with {@code --wait-pid} did not end within the grace period (update contract). */
    WAIT_TIMEOUT(12, "error.waitTimeout"),
    /** The run needs administrator rights and no way to ask for them is available. */
    ELEVATION_UNAVAILABLE(13, "error.elevation");

    private final int exitCode;
    private final String messageKey;

    ErrorCode(int exitCode, String messageKey) {
        this.exitCode = exitCode;
        this.messageKey = messageKey;
    }

    /** The process exit status of the silent mode for this cause. */
    public int exitCode() {
        return exitCode;
    }

    /** Bundle key of the user's text: what happened and why. */
    public String messageKey() {
        return messageKey;
    }

    /** Bundle key of the hint: what the user can do about it. */
    public String hintKey() {
        return messageKey + ".hint";
    }
}
