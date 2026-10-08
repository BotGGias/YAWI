package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.Platform;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Where the PATH additions of the {@code <integration>} block go on Linux and
 * macOS. A per-user installation edits the login shells' profiles in
 * the home directory; a system-wide one owns a drop-in under
 * {@code /etc/profile.d}. Windows keeps the PATH in the registry, not a file,
 * and has no target here (see {@link WindowsPathRegistry}).
 *
 * <p>Shell profiles are the user's property, so {@code ~/.profile} (the POSIX
 * baseline, created if missing) is the only per-user file the installer will
 * create; {@code ~/.zshrc} is only touched when it already exists. The
 * system-wide drop-in belongs to the installer entirely and is removed on
 * uninstall.
 */
public final class PathLayout {

    private PathLayout() {
    }

    /**
     * A file to edit and how to treat it.
     *
     * @param path            the profile file
     * @param createIfMissing whether the step may create it when it is absent
     * @param installerOwned  whether the installer owns the whole file (delete it when the block is the last thing in it)
     */
    public record Target(Path path, boolean createIfMissing, boolean installerOwned) {
    }

    /** The profile files to edit for this scope, in the order they are written. */
    public static List<Target> targets(Platform platform, boolean systemWide, String productId) {
        if (systemWide) {
            return List.of(new Target(systemProfile(productId), true, true));
        }
        Path home = platform.homeDir();
        return List.of(new Target(home.resolve(".profile"), true, false),
                new Target(home.resolve(".zshrc"), false, false));
    }

    /** The system-wide drop-in the installer owns: {@code /etc/profile.d/<productId>.sh}. */
    public static Path systemProfile(String productId) {
        return Path.of("/etc/profile.d", fileName(productId) + ".sh");
    }

    /** Lower-case, only characters a file name safely carries; never empty. */
    private static String fileName(String productId) {
        String cleaned = productId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "app" : cleaned;
    }
}
