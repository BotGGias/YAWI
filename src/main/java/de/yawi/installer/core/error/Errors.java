package de.yawi.installer.core.error;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Translates checked exceptions from the JDK into installer errors at the
 * boundary where they occur, so nothing above the boundary has to know
 * {@code java.nio.file}'s exception zoo.
 *
 * <p>{@code InterruptedException} is deliberately not covered: the code that
 * catches it restores the interrupt flag and throws
 * {@link CancelledException} itself, because only it knows whether the
 * interrupt was a cancel.
 */
public final class Errors {

    private Errors() {
    }

    /**
     * The installer error for an I/O failure at {@code context}: access
     * denied becomes {@link PermissionDeniedException}, a full disk
     * {@link InsufficientSpaceException}, everything else
     * {@link ErrorCode#GENERAL} with the path in the technical text.
     */
    public static InstallerException fromIo(IOException e, Path context) {
        if (e instanceof AccessDeniedException) {
            return new PermissionDeniedException(context, e);
        }
        if (e instanceof FileSystemException fs && isDiskFull(fs)) {
            return new InsufficientSpaceException(context, 0, -1, e);
        }
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new InstallerException(ErrorCode.GENERAL, "I/O error at " + context + ": " + reason, e, reason);
    }

    /** Java has no dedicated exception for ENOSPC; the reason text is the only clue. */
    private static boolean isDiskFull(FileSystemException e) {
        String reason = e.getReason();
        if (reason == null) {
            return false;
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.contains("no space left") || lower.contains("not enough space")
                || lower.contains("disk full") || lower.contains("enospc");
    }
}
