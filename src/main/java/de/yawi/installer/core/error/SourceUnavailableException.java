package de.yawi.installer.core.error;

/** No provider of a {@code <source>} could deliver it (E07, E08). */
public class SourceUnavailableException extends InstallerException {

    private final String sourceId;

    /**
     * @param sourceId  the manifest's source id, shown to the user
     * @param technical what was tried and why it failed, for the log
     */
    public SourceUnavailableException(String sourceId, String technical, Throwable cause) {
        this(sourceId, technical, cause, null);
    }

    /** @param detail the attempts, one per line, for the detail pane */
    public SourceUnavailableException(String sourceId, String technical, Throwable cause, String detail) {
        super(ErrorCode.SOURCE_UNAVAILABLE, ErrorCode.SOURCE_UNAVAILABLE.messageKey(), new Object[] {sourceId},
                "source '" + sourceId + "' unavailable: " + technical, detail, cause);
        this.sourceId = sourceId;
    }

    public String sourceId() {
        return sourceId;
    }
}
