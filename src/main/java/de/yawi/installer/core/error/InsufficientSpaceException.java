package de.yawi.installer.core.error;

import de.yawi.installer.core.manifest.ByteSize;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The target file system has less room than needed - found before the
 * installation or while writing.
 */
public class InsufficientSpaceException extends InstallerException {

    private final Path path;
    private final long requiredBytes;
    private final long availableBytes;

    /**
     * @param availableBytes what is free, or {@code -1} if unknown (the user's
     *                       text then says "unknown" via the root formatting)
     */
    public InsufficientSpaceException(Path path, long requiredBytes, long availableBytes, Throwable cause) {
        // Sizes go in pre-formatted; the bundle text is the same in every language.
        super(ErrorCode.NOT_ENOUGH_SPACE, "not enough space at " + path + ": " + requiredBytes
                        + " bytes required, " + availableBytes + " available", cause,
                ByteSize.format(Math.max(requiredBytes, 0), Locale.ROOT),
                availableBytes < 0 ? "?" : ByteSize.format(availableBytes, Locale.ROOT), path.toString());
        this.path = path;
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    public InsufficientSpaceException(Path path, long requiredBytes, long availableBytes) {
        this(path, requiredBytes, availableBytes, null);
    }

    public Path path() {
        return path;
    }

    public long requiredBytes() {
        return requiredBytes;
    }

    public long availableBytes() {
        return availableBytes;
    }
}
