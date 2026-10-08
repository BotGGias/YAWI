package de.yawi.installer.core.platform;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Linux specifics (XDG base directories); see {@link Platform}. */
public final class LinuxPlatform extends PosixPlatform {

    public LinuxPlatform(Architecture arch, Environment environment) {
        super(arch, environment);
    }

    @Override
    public OperatingSystem os() {
        return OperatingSystem.LINUX;
    }

    private String xdg(String variable, String... fallbackUnderHome) {
        return env(variable).orElseGet(() -> join(homeDir().toString(), fallbackUnderHome).toString());
    }

    @Override
    public Path defaultUserInstallDir(String productId) {
        return join(xdg("XDG_DATA_HOME", ".local", "share"), productId);
    }

    @Override
    public Path defaultSystemInstallDir(String productId) {
        return join("/opt", productId);
    }

    @Override
    public Path applicationMenuDir(boolean systemWide) {
        return systemWide
                ? Path.of("/usr/share/applications")
                : join(xdg("XDG_DATA_HOME", ".local", "share"), "applications");
    }

    @Override
    public Path configDir(String productId) {
        return join(xdg("XDG_CONFIG_HOME", ".config"), productId);
    }

    @Override
    public Path logDir(String productId) {
        return join(xdg("XDG_STATE_HOME", ".local", "state"), productId);
    }

    private static final List<Path> SYSTEM_PREFIXES =
            Stream.of("/opt", "/usr", "/etc", "/var", "/bin", "/sbin", "/lib", "/lib64", "/srv").map(Path::of).toList();

    @Override
    List<Path> systemPrefixes() {
        return SYSTEM_PREFIXES;
    }
}
