package de.yawi.installer.core.platform;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;

/**
 * The installer refuses to run on an operating system or architecture it does
 * not know; random behaviour on an unknown platform would be worse.
 */
public class UnsupportedPlatformException extends InstallerException {

    public UnsupportedPlatformException(String osName, String osArch) {
        super(ErrorCode.UNSUPPORTED_PLATFORM, "This installer does not support " + osName + " (" + osArch + "). "
                + "Supported: Windows, Linux and macOS on x64 or aarch64.", osName, osArch);
    }
}
