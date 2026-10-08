package de.yawi.installer.core.error;

/**
 * The installation needs administrator rights, but there is no way to ask
 * for them: no {@code pkexec}, no {@code sudo} askpass, no terminal,
 * or elevation was switched off ({@code -Dyawi.elevation=none}). The hint
 * suggests a destination in the user's own account.
 */
public class ElevationUnavailableException extends InstallerException {

    public ElevationUnavailableException(String technicalMessage, String detail) {
        super(ErrorCode.ELEVATION_UNAVAILABLE, "error.elevation", new Object[0], technicalMessage, detail, null);
    }

    public ElevationUnavailableException(String technicalMessage) {
        this(technicalMessage, null);
    }
}
