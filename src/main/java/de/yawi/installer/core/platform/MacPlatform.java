package de.yawi.installer.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** macOS specifics; see {@link Platform}. */
public final class MacPlatform extends PosixPlatform {

    public MacPlatform(Architecture arch, Environment environment) {
        super(arch, environment);
    }

    @Override
    public OperatingSystem os() {
        return OperatingSystem.MACOS;
    }

    @Override
    public Path defaultUserInstallDir(String productId) {
        return join(homeDir().toString(), "Applications", productId);
    }

    @Override
    public Path defaultSystemInstallDir(String productId) {
        return join("/Applications", productId);
    }

    /** No start menu on macOS: the Applications folder is what Launchpad shows. */
    @Override
    public Path applicationMenuDir(boolean systemWide) {
        return systemWide ? Path.of("/Applications") : join(homeDir().toString(), "Applications");
    }

    @Override
    public Path configDir(String productId) {
        return join(homeDir().toString(), "Library", "Application Support", productId);
    }

    @Override
    public Path logDir(String productId) {
        return join(homeDir().toString(), "Library", "Logs", productId);
    }

    private static final List<Path> SYSTEM_PREFIXES =
            Stream.of("/Applications", "/Library", "/System", "/usr", "/opt", "/etc", "/var", "/bin", "/sbin", "/private").map(Path::of).toList();

    @Override
    List<Path> systemPrefixes() {
        return SYSTEM_PREFIXES;
    }
}
