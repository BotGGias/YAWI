package de.yawi.installer.core.engine;

import de.yawi.installer.core.elevation.ElevatedWorker;
import de.yawi.installer.core.engine.step.Steps;
import de.yawi.installer.core.integration.AssociationSpec;
import de.yawi.installer.core.integration.DesktopDatabase;
import de.yawi.installer.core.integration.MimeApps;
import de.yawi.installer.core.integration.MimeDatabase;
import de.yawi.installer.core.integration.PathLayout;
import de.yawi.installer.core.integration.ShellProfile;
import de.yawi.installer.core.integration.WindowsAssociationScript;
import de.yawi.installer.core.integration.WindowsPathRegistry;
import de.yawi.installer.core.manifest.Command;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What {@link Rollback} and {@link Uninstaller} have in common: running the
 * {@code <rollback>} commands of a {@code run-command} step, deleting a
 * recorded path, and removing a directory only if it is empty. Nothing here
 * decides <em>what</em> to undo; that is the caller's walk over the record.
 */
final class Undo {

    private static final Logger LOG = LoggerFactory.getLogger(Undo.class);

    /** Why a step's reverse commands did not run. */
    enum NoReverse {
        /** The step is not a {@code run-command} or declares no {@code <rollback>} for this OS. */
        NONE_DECLARED,
        /** The step is {@code elevated} and this process is not; its reverse needs elevation too. */
        ELEVATED,
        /** The manifest came from the network without a valid signature; its commands never run. */
        UNTRUSTED
    }

    private Undo() {
    }

    /**
     * After shortcuts came out: refreshes the desktop database of
     * an applications folder a {@code .desktop} file was removed from - only
     * there, the tool would litter the desktop with its cache. Best effort.
     */
    static void refreshMenus(Platform platform, Collection<Path> removed) {
        List<Path> menus = List.of(platform.applicationMenuDir(false), platform.applicationMenuDir(true));
        removed.stream()
                .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".desktop"))
                .map(Path::getParent).filter(Objects::nonNull).distinct()
                .filter(menus::contains)
                .forEach(dir -> DesktopDatabase.refresh(platform, dir));
    }

    /**
     * After a MIME package came out: refreshes the MIME cache of a
     * tree a {@code packages/*.xml} file was removed from ({@code ~/.local/share/mime}
     * or {@code /usr/share/mime}). Best effort, like {@link #refreshMenus}.
     */
    static void refreshMimeDatabase(Platform platform, Collection<Path> removed) {
        removed.stream()
                .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".xml"))
                .map(Path::getParent).filter(Objects::nonNull)
                .filter(dir -> dir.getFileName() != null && "packages".equals(dir.getFileName().toString()))
                .map(Path::getParent).filter(Objects::nonNull)
                .distinct()
                .forEach(mimeDir -> MimeDatabase.refresh(platform, mimeDir));
    }

    /**
     * Reverses a file association. The files it created (MIME package,
     * handler {@code .desktop}, the macOS plist inside the bundle) are removed by
     * the caller's recorded-file pass; here the non-file changes are undone,
     * reconstructed from the manifest: the Linux {@code mimeapps.list} default
     * and the Windows registry keys. Best effort - a failure is logged, never
     * thrown, so it never fails an uninstall.
     */
    static void reverseAssociation(ExecutionContext context, InstallStep.FileAssociation step) {
        String productId = context.manifest().product().id();
        String extension = step.association().extension();
        switch (context.platform().os()) {
            case LINUX -> reverseLinuxAssociation(context, productId, extension);
            case WINDOWS -> reverseWindowsAssociation(context, productId, extension);
            case MACOS -> LOG.debug("Association {}: the document types go with the bundle on macOS", extension);
            case UNKNOWN -> {
            }
        }
    }

    private static void reverseLinuxAssociation(ExecutionContext context, String productId, String extension) {
        String mimeType = AssociationSpec.mimeType(productId, extension);
        String desktop = AssociationSpec.baseName(productId, extension) + ".desktop";
        Path mimeAppsList = context.platform().configDir(productId).getParent().resolve("mimeapps.list");
        try {
            if (MimeApps.removeDefault(mimeAppsList, mimeType, desktop)) {
                LOG.info("Removed the default application for {} from {}", mimeType, mimeAppsList);
            }
        } catch (IOException e) {
            LOG.warn("Could not update {}: {}", mimeAppsList, e.toString());
        }
    }

    private static void reverseWindowsAssociation(ExecutionContext context, String productId, String extension) {
        boolean systemWide = context.platform().isSystemPath(context.destination());
        for (List<String> command : WindowsAssociationScript.deleteCommands(productId, extension, systemWide)) {
            runQuietly(command);
        }
    }

    /**
     * Reverses the PATH entries. On Linux/macOS the marked block is
     * cut from the shell profiles (an installer-owned {@code /etc/profile.d}
     * drop-in is deleted when nothing else is left in it); on Windows the
     * directories are removed from the registry PATH. Best effort - a failure is
     * logged, never thrown, so it never fails an uninstall. The change takes
     * effect in new sessions only.
     */
    static void reversePathEntries(ExecutionContext context, InstallStep.PathEntries step) {
        switch (context.platform().os()) {
            case LINUX, MACOS -> reverseUnixPath(context);
            case WINDOWS -> reverseWindowsPath(context, step);
            case UNKNOWN -> {
            }
        }
    }

    private static void reverseUnixPath(ExecutionContext context) {
        String productId = context.manifest().product().id();
        boolean systemWide = context.platform().isSystemPath(context.destination());
        for (PathLayout.Target target : PathLayout.targets(context.platform(), systemWide, productId)) {
            Path file = target.path();
            try {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String content = Files.readString(file, StandardCharsets.UTF_8);
                ShellProfile.Removal removal = ShellProfile.removeBlock(content, productId);
                if (!removal.removed()) {
                    continue;
                }
                if (target.installerOwned() && removal.content().isBlank()) {
                    Files.deleteIfExists(file);
                    LOG.info("Removed the PATH drop-in {}", file);
                } else {
                    Files.writeString(file, removal.content(), StandardCharsets.UTF_8);
                    LOG.info("Removed the PATH block from {}", file);
                }
            } catch (IOException e) {
                LOG.warn("Could not update {}: {}", file, e.toString());
            }
        }
    }

    private static void reverseWindowsPath(ExecutionContext context, InstallStep.PathEntries step) {
        boolean systemWide = context.platform().isSystemPath(context.destination());
        List<String> dirs = new ArrayList<>();
        for (String entry : step.entries()) {
            dirs.add(Path.of(context.resolve(entry)).toAbsolutePath().normalize().toString());
        }
        String current = queryWindowsPath(systemWide);
        WindowsPathRegistry.removed(current, dirs)
                .ifPresent(value -> runQuietly(WindowsPathRegistry.addCommand(value, systemWide)));
    }

    /** The current Windows PATH value; empty when unset or {@code reg} cannot be run. */
    private static String queryWindowsPath(boolean systemWide) {
        try {
            Process process = new ProcessBuilder(WindowsPathRegistry.queryCommand(systemWide))
                    .redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
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

    /** Runs a short command whose failure is only a log line (a {@code reg delete} of a missing key is normal). */
    private static void runQuietly(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("{} did not finish in time", String.join(" ", command));
            } else if (process.exitValue() != 0) {
                LOG.debug("{} exited with {}", String.join(" ", command), process.exitValue());
            }
        } catch (IOException e) {
            LOG.warn("{} could not be run: {}", String.join(" ", command), e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether the reverse of {@code definition} can run at all, and if not why.
     *
     * @return empty if it can run
     */
    static java.util.Optional<NoReverse> reverseBlocked(ExecutionContext context, InstallStep definition) {
        if (!(definition instanceof InstallStep.RunCommand step)) {
            return java.util.Optional.of(NoReverse.NONE_DECLARED);
        }
        if (step.rollbackFor(context.platform().os()).isEmpty()) {
            return java.util.Optional.of(NoReverse.NONE_DECLARED);
        }
        if (step.elevated() && !context.platform().isElevated() && !ElevatedWorker.isChild()) {
            return java.util.Optional.of(NoReverse.ELEVATED);
        }
        if (!context.manifest().origin().isTrusted()) {
            return java.util.Optional.of(NoReverse.UNTRUSTED);
        }
        return java.util.Optional.empty();
    }

    /**
     * Runs the step's reverse commands for the current OS as a synthetic
     * {@code run-command} step {@code <id>#rollback}. The caller checks
     * {@link #reverseBlocked} first.
     *
     * @throws RuntimeException whatever the command step throws (non-zero exit, timeout, cancellation)
     */
    static void reverseStep(ExecutionContext context, InstallStep.RunCommand step) {
        OperatingSystem os = context.platform().os();
        List<Command> reverse = step.rollbackFor(os);
        InstallStep.RunCommand reversed = new InstallStep.RunCommand(step.id() + "#rollback", step.weight(), 0,
                step.timeoutSeconds(), InstallStep.OnFailure.ABORT, false, step.workingDir(), step.env(),
                Map.of(os, reverse), Map.of());
        LOG.info("Step {}: running {} reverse command(s)", step.id(), reverse.size());
        Steps.DEFAULT.create(reversed).execute(context);
    }

    /**
     * Deletes a recorded path: a directory (a copied tree) with everything in
     * it, a file or symlink as such.
     *
     * @return whether something was deleted
     */
    static boolean deletePath(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Backup.deleteTree(path);
            return true;
        }
        return Files.deleteIfExists(path);
    }

    /**
     * Deletes a directory if it exists and is empty.
     *
     * @return whether it was deleted
     */
    static boolean deleteIfEmpty(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
                LOG.info("Keeping {}: not empty", dir);
                return false;
            }
        }
        Files.delete(dir);
        return true;
    }
}
