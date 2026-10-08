package de.yawi.installer.core.error;

import java.nio.file.Path;

/**
 * The process may not write where it has to. The hint points to "for all
 * users"; decides whether elevation is the answer.
 */
public class PermissionDeniedException extends InstallerException {

    private final Path path;

    public PermissionDeniedException(Path path, Throwable cause) {
        super(ErrorCode.PERMISSION_DENIED, "permission denied: " + path, cause, path.toString());
        this.path = path;
    }

    public PermissionDeniedException(Path path) {
        this(path, null);
    }

    public Path path() {
        return path;
    }
}
