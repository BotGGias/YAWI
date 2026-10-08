package de.yawi.installer.core.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The one place where Windows, Linux and macOS differ.
 *
 * <p>Everything OS specific in the installer - default paths, how a script is
 * invoked, quoting, free space, permission checks - goes through this
 * interface. There are no {@code os.name} checks anywhere else; use
 * {@link PlatformFactory#current()} to obtain the implementation.
 *
 * <p>Name clash: the UI layer also uses {@code javafx.application.Platform}.
 * Never import both in one class; UI code reaches this one through
 * {@code InstallerModel.getPlatform()} without naming the type.
 */
public interface Platform {

    OperatingSystem os();

    Architecture arch();

    /** Environment variables and system properties this platform reads. */
    Environment environment();

    // --- E02-S02 default locations --------------------------------------

    /** Built-in per-user install location, e.g. {@code %LOCALAPPDATA%\<id>} or {@code ~/.local/share/<id>}. */
    Path defaultUserInstallDir(String productId);

    /** Built-in system-wide install location, e.g. {@code %ProgramFiles%\<id>} or {@code /opt/<id>}. */
    Path defaultSystemInstallDir(String productId);

    /**
     * The install directory to propose: the manifest's default for this OS
     * (with its variables expanded) if the packager gave one, else the
     * built-in one. The caller picks {@code manifestDefault} from
     * {@code DestinationConfig.defaultFor(os())} / {@code systemWideFor(os())}.
     *
     * @param manifestDefault the manifest text for this OS, or {@code null}
     */
    Path installDir(String manifestDefault, String productId, boolean systemWide);

    /**
     * Expands the platform's variable syntax - {@code %NAME%} on Windows,
     * {@code $NAME}, {@code ${NAME}} and a leading {@code ~} elsewhere.
     * Unknown variables are left untouched.
     */
    String expandVariables(String text);

    Path homeDir();

    Path desktopDir();

    /** Where application launchers go: Start Menu programs, {@code applications/}, or the Applications folder. */
    Path applicationMenuDir(boolean systemWide);

    Path configDir(String productId);

    Path tempDir();

    /** Log location per platform convention; E17-S03 moves the log file here. */
    Path logDir(String productId);

    // --- running commands ----------------------------------------

    /**
     * The argument list that runs a command line through the platform's
     * shell: {@code cmd.exe /c <line>} or {@code /bin/sh -c <line>}. Paths
     * embedded in the line must be passed through {@link #quote(Path)}.
     */
    List<String> shellCommand(String commandLine);

    /** The path quoted for the platform's shell, safe for spaces and quotes. */
    String quote(Path path);

    /** Sets the executable bit on Linux/macOS; a no-op on Windows. */
    void makeExecutable(Path file) throws IOException;

    /** {@code .bat} or {@code .sh}. */
    String scriptExtension();

    // --- space and permissions ------------------------------------

    /**
     * Usable bytes on the file system that holds {@code path}. The path need
     * not exist yet; the nearest existing ancestor is used then.
     */
    long usableSpace(Path path) throws IOException;

    /**
     * Whether files can actually be created in {@code dir}, proven with a
     * probe file rather than read from permission flags. For a directory that
     * does not exist yet the nearest existing ancestor is probed, i.e. whether
     * it could be created. An existing regular file is never writable as a
     * directory.
     */
    boolean isWritable(Path dir);

    /** Under one of the platform's known system locations ({@code %ProgramFiles%}, {@code /opt}, {@code /Applications}, …). */
    boolean isSystemPath(Path path);

    /** Under the user's home or temp directory - where elevation is never the answer to a permission problem. */
    boolean isUserSpacePath(Path path);

    /**
     * Whether writing to {@code dir} needs elevated rights: not already
     * elevated, outside the user's space, and not writable as we are.
     */
    boolean requiresElevation(Path dir);

    /** Whether this process already runs with elevated rights (root / Administrator). Cached. */
    boolean isElevated();
}
