package de.yawi.installer.core.error;

/** A checksum or signature did not match; the file must not be used. */
public class IntegrityException extends InstallerException {

    private final String fileName;

    /**
     * @param fileName  the file as the user knows it
     * @param technical expected vs. actual, for the log - never shown
     */
    public IntegrityException(String fileName, String technical) {
        this(fileName, technical, null);
    }

    /** @param detail the attempts, one per line, for the detail pane */
    public IntegrityException(String fileName, String technical, String detail) {
        super(ErrorCode.INTEGRITY_FAILED, ErrorCode.INTEGRITY_FAILED.messageKey(), new Object[] {fileName},
                "integrity check of " + fileName + " failed: " + technical, detail, null);
        this.fileName = fileName;
    }

    public String fileName() {
        return fileName;
    }
}
