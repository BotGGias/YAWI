package de.yawi.installer.core.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** What Linux and macOS share: {@code /} paths, {@code $VAR} syntax, {@code ~}. */
abstract class PosixPlatform extends AbstractPlatform {

    private static final long ID_TIMEOUT_SECONDS = 2;

    /** {@code ${NAME}}, {@code $NAME}; a leading {@code ~} is handled separately. */
    private static final Pattern VARIABLE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)");

    PosixPlatform(Architecture arch, Environment environment) {
        super(arch, environment);
    }

    @Override
    String separator() {
        return "/";
    }

    @Override
    Pattern variablePattern() {
        return VARIABLE;
    }

    @Override
    public String expandVariables(String text) {
        String expanded = super.expandVariables(text);
        if (expanded.equals("~") || expanded.startsWith("~/")) {
            expanded = homeDir() + expanded.substring(1);
        }
        return expanded;
    }

    @Override
    public Path homeDir() {
        return Path.of(env("HOME").orElseGet(this::userHome));
    }

    @Override
    public Path desktopDir() {
        return join(homeDir().toString(), "Desktop");
    }

    // --- commands --------------------------------------------------------

    @Override
    public List<String> shellCommand(String commandLine) {
        return List.of("/bin/sh", "-c", commandLine);
    }

    /** Single quotes; an embedded quote becomes {@code '\''}. */
    @Override
    public String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    @Override
    public void makeExecutable(Path file) throws IOException {
        Set<PosixFilePermission> permissions;
        try {
            permissions = EnumSet.copyOf(Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException e) {
            throw new IOException("File system of " + file + " does not support POSIX permissions", e);
        }
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if (permissions.contains(PosixFilePermission.GROUP_READ)) {
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        Files.setPosixFilePermissions(file, permissions);
    }

    @Override
    public String scriptExtension() {
        return ".sh";
    }

    // --- elevation -------------------------------------------------------

    /** root by name, else {@code id -u} == 0; anything that goes wrong counts as not elevated. */
    @Override
    boolean detectElevated() {
        if (property("user.name").filter("root"::equals).isPresent()) {
            return true;
        }
        try {
            Process process = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(ID_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && output.equals("0");
        } catch (IOException | SecurityException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
