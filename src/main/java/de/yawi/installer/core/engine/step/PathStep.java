package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integration.PathLayout;
import de.yawi.installer.core.integration.ShellProfile;
import de.yawi.installer.core.integration.WindowsPathRegistry;
import de.yawi.installer.core.integrity.PathEscapeException;
import de.yawi.installer.core.manifest.InstallStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code pathEntry}: the {@code <pathEntry>} directories of the
 * manifest's {@code <integration>} block, added to the search PATH so the
 * software's command-line tools can be called without a full path. Each entry
 * must lie inside the destination. Linux/macOS write a marked block into the
 * user's shell profiles ({@code ~/.profile}, {@code ~/.zshrc}) or, for a
 * system-wide install, an owned drop-in under {@code /etc/profile.d}; Windows
 * appends to the registry PATH with a length check. Duplicate entries are not
 * added twice, existing files are only appended to, and the change takes effect
 * in new sessions only. A failure here never aborts the installation.
 */
final class PathStep extends AbstractFileStep {

    private static final Logger LOG = LoggerFactory.getLogger(PathStep.class);
    /** How long {@code reg} may take. */
    static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    private final InstallStep.PathEntries step;

    PathStep(InstallStep.PathEntries step) {
        super(step);
        this.step = step;
    }

    @Override
    void run(ExecutionContext context) throws IOException {
        List<String> dirs = resolvedDirs(context);
        if (dirs.isEmpty()) {
            return;
        }
        boolean systemWide = context.platform().isSystemPath(context.destination());
        switch (context.platform().os()) {
            case LINUX, MACOS -> unix(context, dirs, systemWide);
            case WINDOWS -> windows(context, dirs, systemWide);
            case UNKNOWN -> throw new StepFailedException(id(), "unsupported platform", null);
        }
        context.listener().output("path: the new PATH takes effect in new sessions");
    }

    // --- Linux/macOS: a marked block in the shell profiles ----------------------

    private void unix(ExecutionContext context, List<String> dirs, boolean systemWide) throws IOException {
        String productId = context.manifest().product().id();
        for (PathLayout.Target target : PathLayout.targets(context.platform(), systemWide, productId)) {
            Path file = target.path();
            boolean exists = Files.exists(file);
            if (!exists && !target.createIfMissing()) {
                continue;
            }
            String content = exists ? Files.readString(file, StandardCharsets.UTF_8) : "";
            String updated = ShellProfile.appendBlock(content, productId, dirs);
            if (updated.equals(content)) {
                continue; // already there (dedup)
            }
            if (target.installerOwned() && !exists) {
                // A drop-in the installer owns: record it so the uninstaller deletes the whole file.
                writeExternal(context, file, updated.getBytes(StandardCharsets.UTF_8));
            } else {
                // The user's own profile: only appended to, never recorded; the uninstaller removes the block.
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, updated, StandardCharsets.UTF_8);
                context.listener().stepProgress(1, file.toString());
            }
        }
    }

    // --- Windows: the registry PATH with a length check --------------------------

    private void windows(ExecutionContext context, List<String> dirs, boolean systemWide) {
        String current = query(systemWide);
        var appended = WindowsPathRegistry.appended(current, dirs);
        if (appended.isEmpty()) {
            context.listener().output("path: every entry is already in the PATH");
            return;
        }
        String value = appended.get();
        if (value.length() > WindowsPathRegistry.MAX_PATH_LENGTH) {
            throw new StepFailedException(id(), "the PATH would exceed " + WindowsPathRegistry.MAX_PATH_LENGTH
                    + " characters (" + value.length() + "); not appending", null);
        }
        runOrFail(WindowsPathRegistry.addCommand(value, systemWide));
        context.listener().stepProgress(1, "PATH += " + String.join(";",
                WindowsPathRegistry.missing(current, dirs)));
    }

    /** The current PATH value; empty when the value is unset or {@code reg} cannot be run. */
    private String query(boolean systemWide) {
        try {
            Process process = new ProcessBuilder(WindowsPathRegistry.queryCommand(systemWide))
                    .redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return WindowsPathRegistry.parseValue(output).orElse("");
        } catch (IOException e) {
            LOG.warn("Cannot read the current PATH: {}", e.toString());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    /** Runs a {@code reg} command whose failure is this step's failure (the engine maps it to continue). */
    private void runOrFail(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            if (!process.waitFor(TOOL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new StepFailedException(id(), String.join(" ", command) + " did not finish within "
                        + TOOL_TIMEOUT.toSeconds() + " s", null);
            }
            if (process.exitValue() != 0) {
                throw new StepFailedException(id(), String.join(" ", command) + " exited with "
                        + process.exitValue() + (output.isEmpty() ? "" : ": " + output), null);
            }
        } catch (IOException e) {
            throw new StepFailedException(id(), String.join(" ", command) + " could not be run: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException(id(), "interrupted while updating the PATH", e);
        }
    }

    // --- shared ------------------------------------------------------------------

    /** The manifest entries resolved, confined to the destination, and absolute; an escape is this step's failure. */
    private List<String> resolvedDirs(ExecutionContext context) {
        List<String> dirs = new ArrayList<>();
        for (String entry : step.entries()) {
            try {
                Path dir = guard(context).confine(Path.of(context.resolve(entry)));
                dirs.add(dir.toString());
            } catch (PathEscapeException e) {
                throw new StepFailedException(id(), "PATH entry must lie inside the destination: "
                        + e.getMessage(), e);
            }
        }
        return dirs;
    }

    @Override
    public String describe(ExecutionContext context) {
        List<String> dirs = new ArrayList<>();
        for (String entry : step.entries()) {
            dirs.add(Path.of(context.resolve(entry)).toAbsolutePath().normalize().toString());
        }
        return "add to PATH: " + String.join(", ", dirs);
    }
}
