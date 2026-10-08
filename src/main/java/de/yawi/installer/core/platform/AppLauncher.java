package de.yawi.installer.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the installed application detached from the installer (
 * {@code --relaunch}, "launch after finish"): the installer may exit
 * right after, the application keeps running with no pipe to the parent.
 * On macOS an {@code .app} bundle goes through {@code open}; on Unix
 * {@code setsid} puts the child into its own session when available.
 */
public final class AppLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(AppLauncher.class);

    private AppLauncher() {
    }

    /**
     * @param target  the executable or {@code .app} bundle
     * @param workDir the child's working directory, usually the installation folder
     * @return the child's PID, or -1 if the target does not exist
     * @throws IOException if the process could not be started
     */
    public static long launchDetached(Platform platform, Path target, Path workDir) throws IOException {
        if (!Files.exists(target)) {
            LOG.warn("Nothing to launch: {} does not exist", target);
            return -1;
        }
        List<String> command = new ArrayList<>();
        if (platform.os() == OperatingSystem.MACOS && target.getFileName().toString().endsWith(".app")) {
            command.add("open");
            command.add(target.toString());
        } else {
            if (platform.os() != OperatingSystem.WINDOWS && Files.isExecutable(Path.of("/usr/bin/setsid"))) {
                command.add("/usr/bin/setsid");
            }
            command.add(target.toString());
        }
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Files.isDirectory(workDir) ? workDir.toFile() : null)
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        LOG.info("Launched {} (pid {})", command, process.pid());
        return process.pid();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "NUL" : "/dev/null");
    }
}
