package de.yawi.installer.core.error;

import java.nio.file.Path;

/**
 * {@code --update} was asked to bring an installation up to date, but the
 * destination holds no usable installation record (update contract).
 */
public class NoInstallationException extends InstallerException {

    private final Path destination;

    /** @param technical why the record is unusable: missing, unreadable, other destination */
    public NoInstallationException(Path destination, String technical, Throwable cause) {
        super(ErrorCode.NO_INSTALLATION, ErrorCode.NO_INSTALLATION.messageKey(), new Object[] {destination},
                "no installation under " + destination + ": " + technical, null, cause);
        this.destination = destination;
    }

    public Path destination() {
        return destination;
    }
}
