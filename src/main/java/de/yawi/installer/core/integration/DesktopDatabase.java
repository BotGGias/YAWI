package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.PathLookup;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Refreshes the desktop's cache of {@code .desktop} files after one was
 * written or removed (Linux, {@code update-desktop-database}). Best effort:
 * the tool is optional, the menus pick new files up on their own; a missing
 * or failing tool is a log line, never an error.
 */
public final class DesktopDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopDatabase.class);
    private static final String TOOL = "update-desktop-database";
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private DesktopDatabase() {
    }

    /** @return whether the tool ran and succeeded */
    public static boolean refresh(Platform platform, Path applicationsDir) {
        if (platform.os() != OperatingSystem.LINUX || !Files.isDirectory(applicationsDir)) {
            return false;
        }
        Optional<Path> tool = find(platform);
        if (tool.isEmpty()) {
            LOG.debug("{} not found on PATH; the menu cache is not refreshed", TOOL);
            return false;
        }
        try {
            Process process = new ProcessBuilder(tool.get().toString(), applicationsDir.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.warn("{} {} did not finish within {} s", TOOL, applicationsDir, TIMEOUT.toSeconds());
                return false;
            }
            if (process.exitValue() != 0) {
                LOG.warn("{} {} exited with {}", TOOL, applicationsDir, process.exitValue());
                return false;
            }
            LOG.info("Refreshed the desktop database for {}", applicationsDir);
            return true;
        } catch (IOException e) {
            LOG.warn("{} could not be run: {}", TOOL, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Optional<Path> find(Platform platform) {
        return PathLookup.find(platform, TOOL);
    }
}
