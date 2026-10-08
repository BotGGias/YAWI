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
 * Rebuilds the MIME cache after a package file was written or removed (
 * {@code update-mime-database}). Best effort, exactly like
 * {@link DesktopDatabase}: a missing or failing tool is a log line, never an
 * error.
 */
public final class MimeDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(MimeDatabase.class);
    private static final String TOOL = "update-mime-database";
    static final Duration TIMEOUT = Duration.ofSeconds(15);

    private MimeDatabase() {
    }

    /** @return whether the tool ran and succeeded */
    public static boolean refresh(Platform platform, Path mimeDir) {
        if (platform.os() != OperatingSystem.LINUX || !Files.isDirectory(mimeDir)) {
            return false;
        }
        Optional<Path> tool = PathLookup.find(platform, TOOL);
        if (tool.isEmpty()) {
            LOG.debug("{} not found on PATH; the MIME cache is not refreshed", TOOL);
            return false;
        }
        try {
            Process process = new ProcessBuilder(tool.get().toString(), mimeDir.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.warn("{} {} did not finish within {} s", TOOL, mimeDir, TIMEOUT.toSeconds());
                return false;
            }
            if (process.exitValue() != 0) {
                LOG.warn("{} {} exited with {}", TOOL, mimeDir, process.exitValue());
                return false;
            }
            LOG.info("Refreshed the MIME database for {}", mimeDir);
            return true;
        } catch (IOException e) {
            LOG.warn("{} could not be run: {}", TOOL, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
