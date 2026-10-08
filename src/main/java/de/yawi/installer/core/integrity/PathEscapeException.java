package de.yawi.installer.core.integrity;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;

import java.nio.file.Path;

/**
 * A manifest step or an archive entry tried to reach outside the allowed
 * roots. That is a defect of the package, not of the user's machine, hence
 * {@link ErrorCode#MANIFEST_INVALID}.
 */
public class PathEscapeException extends InstallerException {

    private final Path path;

    public PathEscapeException(Path path, String technical) {
        super(ErrorCode.MANIFEST_INVALID, "error.pathEscape", new Object[] {path.toString()},
                "path escapes the allowed roots: " + path + " (" + technical + ")", null, null);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
