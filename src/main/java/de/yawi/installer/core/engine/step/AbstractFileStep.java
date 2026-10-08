package de.yawi.installer.core.engine.step;

import de.yawi.installer.core.engine.Backup;
import de.yawi.installer.core.engine.ExecutableStep;
import de.yawi.installer.core.engine.ExecutionContext;
import de.yawi.installer.core.error.Errors;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.integrity.PathGuard;
import de.yawi.installer.core.manifest.InstallStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the file steps share: resolving a manifest path (placeholders, then
 * the {@link PathGuard}), creating parent directories with a record entry
 * for each one that is new, moving an existing target to the backup before
 * it is overwritten, and turning I/O failures into installer
 * errors at the step boundary.
 */
abstract class AbstractFileStep implements ExecutableStep {

    private final InstallStep definition;

    AbstractFileStep(InstallStep definition) {
        this.definition = definition;
    }

    @Override
    public InstallStep definition() {
        return definition;
    }

    String id() {
        return definition.id();
    }

    /** The work; may throw {@link IOException}, which {@link #execute} translates. */
    abstract void run(ExecutionContext context) throws IOException;

    @Override
    public final void execute(ExecutionContext context) {
        try {
            run(context);
        } catch (IOException e) {
            InstallerException translated = Errors.fromIo(e, context.destination());
            if (translated.code() == de.yawi.installer.core.error.ErrorCode.GENERAL) {
                // A plain I/O problem is this step's failure; rights and space keep their own class.
                throw new StepFailedException(id(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
            }
            throw translated;
        }
    }

    /** Targets may only lie in the destination or the installer's scratch directory. */
    static PathGuard guard(ExecutionContext context) {
        return new PathGuard(context.destination(), context.workDir());
    }

    /** A manifest path with placeholders resolved and confined to the allowed roots. */
    static Path target(ExecutionContext context, String manifestPath) {
        return guard(context).confine(Path.of(context.resolve(manifestPath)));
    }

    /** What {@link #prepareTarget} found, so {@link #written} knows whether to record a creation. */
    enum Prepared {
        /** Nothing was there: record the file as created once it is written. */
        CREATED,
        /** Something was there and went to the backup; the record already holds the replacement. */
        REPLACED,
        /** This run already created or replaced the path: just overwrite. */
        TRACKED
    }

    /**
     * Makes room for a write: an existing target (file, directory, symlink)
     * that this run does not yet own is moved to the backup and recorded as
     * replaced <em>before</em> anything is written, so a failure during the
     * write is undone from the backup. Call {@link #written} afterwards.
     */
    static Prepared prepareTarget(ExecutionContext context, Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Prepared.CREATED;
        }
        if (context.record().tracks(target)) {
            return Prepared.TRACKED;
        }
        Optional<Path> saved = Backup.of(context).save(target);
        if (saved.isPresent()) {
            context.record().fileReplaced(target, saved.get());
            return Prepared.REPLACED;
        }
        // Outside the destination (scratch directory): overwritten without a backup.
        return Prepared.TRACKED;
    }

    /** Records the write that followed {@link #prepareTarget}. */
    static void written(ExecutionContext context, Path target, Prepared prepared) {
        if (prepared == Prepared.CREATED) {
            context.record().fileCreated(target);
        }
    }

    /**
     * Writes a file that lives <em>outside</em> the destination (a menu entry,
     * a MIME package, ...), with its parent directories, a backup of anything
     * that was there, and a record entry, so a rollback restores the previous
     * state and the uninstaller removes what this run added. Shared by
     * the shortcut and association steps.
     */
    static void writeExternal(ExecutionContext context, Path file, byte[] content) throws IOException {
        createDirectories(context, file.getParent());
        boolean existed = prepareExternal(context, file);
        Files.write(file, content);
        if (!existed) {
            context.record().fileCreated(file);
        }
        context.listener().stepProgress(1, file.toString());
    }

    /**
     * Copies a file this run does not own yet into the backup and records the
     * replacement, so a rollback restores it.
     *
     * @return whether something was there
     */
    static boolean prepareExternal(ExecutionContext context, Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (!context.record().tracks(file)) {
            Path saved = Backup.of(context).saveExternal(file);
            context.record().fileReplaced(file, saved);
        }
        return true;
    }

    /**
     * Creates {@code dir} and its parents, recording each directory that did
     * not exist before (outermost first), so a rollback removes only those.
     */
    static void createDirectories(ExecutionContext context, Path dir) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = dir;
        while (current != null && !Files.exists(current)) {
            missing.add(0, current);
            current = current.getParent();
        }
        if (missing.isEmpty()) {
            if (!Files.isDirectory(dir)) {
                throw new IOException(dir + " exists but is not a directory");
            }
            return;
        }
        Files.createDirectories(dir);
        missing.forEach(context.record()::directoryCreated);
    }
}
