package de.yawi.installer.core.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Windows specifics; see {@link Platform}. */
public final class WindowsPlatform extends AbstractPlatform {

    private static final Pattern VARIABLE = Pattern.compile("%([^%]+)%");

    public WindowsPlatform(Architecture arch, Environment environment) {
        super(arch, environment);
    }

    @Override
    public OperatingSystem os() {
        return OperatingSystem.WINDOWS;
    }

    @Override
    String separator() {
        return "\\";
    }

    @Override
    Pattern variablePattern() {
        return VARIABLE;
    }

    /** Windows environment variables are case-insensitive. */
    @Override
    Optional<String> variable(String name) {
        return environment().env(name)
                .or(() -> environment().env(name.toUpperCase(Locale.ROOT)));
    }

    // --- locations -------------------------------------------------------

    @Override
    public Path homeDir() {
        return Path.of(env("USERPROFILE").orElseGet(this::userHome));
    }

    private String localAppData() {
        return env("LOCALAPPDATA").orElseGet(() -> join(homeDir().toString(), "AppData", "Local").toString());
    }

    private String appData() {
        return env("APPDATA").orElseGet(() -> join(homeDir().toString(), "AppData", "Roaming").toString());
    }

    String programFiles() {
        return env("ProgramFiles").orElse("C:\\Program Files");
    }

    private String programData() {
        return env("ProgramData").orElse("C:\\ProgramData");
    }

    @Override
    public Path defaultUserInstallDir(String productId) {
        return join(localAppData(), productId);
    }

    @Override
    public Path defaultSystemInstallDir(String productId) {
        return join(programFiles(), productId);
    }

    @Override
    public Path desktopDir() {
        return join(homeDir().toString(), "Desktop");
    }

    @Override
    public Path applicationMenuDir(boolean systemWide) {
        String base = systemWide ? programData() : appData();
        return join(base, "Microsoft", "Windows", "Start Menu", "Programs");
    }

    @Override
    public Path configDir(String productId) {
        return join(appData(), productId);
    }

    @Override
    public Path logDir(String productId) {
        return join(localAppData(), productId, "logs");
    }

    // --- commands --------------------------------------------------------

    @Override
    public List<String> shellCommand(String commandLine) {
        return List.of("cmd.exe", "/c", commandLine);
    }

    /** Double quotes; an embedded quote is escaped as {@code \"} (MSVCRT rules). */
    @Override
    public String quote(Path path) {
        return "\"" + path.toString().replace("\"", "\\\"") + "\"";
    }

    /** Windows has no executable bit; extensions decide. */
    @Override
    public void makeExecutable(Path file) {
    }

    @Override
    public String scriptExtension() {
        return ".bat";
    }

    // --- elevation -------------------------------------------------------

    @Override
    List<Path> systemPrefixes() {
        List<Path> prefixes = new ArrayList<>();
        prefixes.add(Path.of(programFiles()));
        env("ProgramFiles(x86)").map(Path::of).ifPresent(prefixes::add);
        env("ProgramW6432").map(Path::of).ifPresent(prefixes::add);
        prefixes.add(Path.of(programData()));
        prefixes.add(Path.of(env("SystemRoot").orElse("C:\\Windows")));
        return prefixes;
    }

    /** Only an elevated process may create files under Program Files. */
    @Override
    boolean detectElevated() {
        Path probe = Path.of(programFiles()).resolve(".yawi-installer-elevation-" + UUID.randomUUID());
        try {
            Files.createFile(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        } finally {
            try {
                Files.deleteIfExists(probe);
            } catch (IOException | SecurityException ignored) {
                // nothing to clean up if it could not be created
            }
        }
    }
}
