package de.yawi.installer.core.error;

/**
 * The user stopped the run - through Cancel, the window's close button or by
 * declining the elevation prompt. Not a defect: handlers log it
 * at info level and skip the error dialog, but the exit code is
 * {@link ErrorCode#CANCELLED} so scripts can tell it from success.
 */
public class CancelledException extends InstallerException {

    public CancelledException() {
        this("cancelled by user", null);
    }

    public CancelledException(String technical, Throwable cause) {
        super(ErrorCode.CANCELLED, technical, cause);
    }
}
