package de.yawi.installer.core.engine;

import de.yawi.installer.core.engine.step.PosixModes;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRecord.CreatedDirectory;
import de.yawi.installer.core.state.InstallationRecord.CreatedFile;
import de.yawi.installer.core.state.InstallationRecord.Entry;
import de.yawi.installer.core.state.InstallationRecord.ModeChanged;
import de.yawi.installer.core.state.InstallationRecord.ReplacedFile;
import de.yawi.installer.core.state.InstallationRecord.StepStarted;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Undoes a failed or cancelled installation from its {@link InstallationRecord}
 * : entries are walked backwards — reverse commands for every
 * {@code run-command} that started, modes put back, replaced files moved back
 * from the {@link Backup}, created files deleted — then the previous record
 * is restored, the backup pruned, and the created directories removed
 * innermost first, each only if empty. What was there before the run is
 * never touched: it was never recorded.
 *
 * <p>Nothing here stops at the first problem: every item is attempted, every
 * failure logged and collected into the {@link Report}. The rollback runs
 * with its own cancellation token and clears a pending thread interrupt
 * first, because the run it undoes was very likely cancelled.
 */
public final class Rollback {

    private static final Logger LOG = LoggerFactory.getLogger(Rollback.class);

    /**
     * What the rollback achieved.
     *
     * @param restored            files moved back from the backup and modes put back
     * @param deleted             created files and directories removed
     * @param stepsWithoutReverse ids of {@code run-command} steps that ran but declare no {@code <rollback>}
     *                            (or could not run it, e.g. elevated)
     * @param failures            one line per item that could not be undone
     * @param leftBehind          backup files that could not be restored and stay in the backup folder
     */
    public record Report(int restored, int deleted, List<String> stepsWithoutReverse, List<String> failures,
                         List<Path> leftBehind) {
        public Report {
            stepsWithoutReverse = List.copyOf(stepsWithoutReverse);
            failures = List.copyOf(failures);
            leftBehind = List.copyOf(leftBehind);
        }

        /** Everything undone; the destination is as it was (steps without a reverse operation aside). */
        public boolean clean() {
            return failures.isEmpty() && leftBehind.isEmpty();
        }

        /** This report and {@code other} as one (the elevated segment's and the parent's, E12-S03). */
        public Report merge(Report other) {
            List<String> noReverse = new java.util.ArrayList<>(stepsWithoutReverse);
            noReverse.addAll(other.stepsWithoutReverse);
            List<String> allFailures = new java.util.ArrayList<>(failures);
            allFailures.addAll(other.failures);
            List<Path> left = new java.util.ArrayList<>(leftBehind);
            left.addAll(other.leftBehind);
            return new Report(restored + other.restored, deleted + other.deleted, noReverse, allFailures, left);
        }
    }

    private final ExecutionContext context;
    private final ExecutionPlan plan;
    private final Backup backup;
    private final Optional<Path> previousRecordCopy;
    private final boolean destinationCreated;

    private final List<String> stepsWithoutReverse = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private int restored;
    private int deleted;
    private final List<Path> removedFiles = new ArrayList<>();
    private int done;
    private int total;

    /**
     * @param context            the run's context via {@link ExecutionContext#forRollback}
     * @param previousRecordCopy where the record of the previous installation was copied before the run
     *                           overwrote it, if there was one
     * @param destinationCreated whether the run created the destination folder itself
     */
    public Rollback(ExecutionContext context, ExecutionPlan plan, Backup backup, Optional<Path> previousRecordCopy,
                    boolean destinationCreated) {
        this.context = context;
        this.plan = plan;
        this.backup = backup;
        this.previousRecordCopy = previousRecordCopy;
        this.destinationCreated = destinationCreated;
    }

    public Report run() {
        if (Thread.interrupted()) {
            LOG.debug("Cleared the thread's interrupt before rolling back");
        }
        InstallationRecord record = context.record();
        List<Entry> entries = record.entries();
        List<CreatedDirectory> directories = entries.stream()
                .filter(CreatedDirectory.class::isInstance).map(CreatedDirectory.class::cast).toList();
        total = entries.size() + (previousRecordCopy.isPresent() ? 1 : 0);
        LOG.info("Rolling back {} entries in {}", entries.size(), record.destination());
        context.listener().rollbackStarted(total);

        // Pass 1: everything but directories, newest first.
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            switch (entry) {
                case StepStarted s -> reverseStep(s.stepId());
                case ModeChanged m -> restoreMode(record, m);
                case ReplacedFile r -> restoreFile(record, r);
                case CreatedFile f -> deleteFile(record, f);
                case CreatedDirectory d -> {
                    // pass 3
                }
                case InstallationRecord.StepFinished f -> progress(f.stepId());
                case InstallationRecord.RunFinished f -> progress("finished");
            }
        }

        Undo.refreshMenus(context.platform(), removedFiles);
        Undo.refreshMimeDatabase(context.platform(), removedFiles);

        // Pass 2: the record of the previous installation, then the backup folder.
        previousRecordCopy.ifPresent(this::restorePreviousRecord);
        List<Path> leftBehind = List.of();
        try {
            leftBehind = backup.prune();
            leftBehind.forEach(p -> LOG.warn("Left in the backup: {}", p));
        } catch (IOException e) {
            failure("backup folder " + backup.root(), e);
        }

        // Pass 3: created directories, innermost first, only if empty.
        for (int i = directories.size() - 1; i >= 0; i--) {
            deleteDirectory(record, directories.get(i));
        }
        if (destinationCreated) {
            deleteIfEmpty(record.destination(), "destination");
        }

        Report report = new Report(restored, deleted, stepsWithoutReverse, failures, leftBehind);
        LOG.info("Rollback finished: {} restored, {} deleted, {} step(s) without reverse operation, {} failure(s)",
                restored, deleted, stepsWithoutReverse.size(), failures.size());
        context.listener().rollbackFinished(report);
        return report;
    }

    // --- run-command ------------------------------------------------------

    private void reverseStep(String stepId) {
        Optional<InstallStep> definition = plan.steps().stream().map(PlannedStep::definition)
                .filter(d -> d.id().equals(stepId)).findFirst();
        if (definition.isPresent() && definition.get() instanceof InstallStep.FileAssociation assoc) {
            try {
                Undo.reverseAssociation(context, assoc);
            } catch (RuntimeException e) {
                failure("reverse of association " + stepId, e);
            }
            progress(stepId);
            return;
        }
        if (definition.isPresent() && definition.get() instanceof InstallStep.PathEntries path) {
            try {
                Undo.reversePathEntries(context, path);
            } catch (RuntimeException e) {
                failure("reverse of PATH entries", e);
            }
            progress(stepId);
            return;
        }
        if (definition.isEmpty() || !(definition.get() instanceof InstallStep.RunCommand step)) {
            progress(stepId);
            return;
        }
        Optional<Undo.NoReverse> blocked = Undo.reverseBlocked(context, step);
        if (blocked.isPresent()) {
            switch (blocked.get()) {
                case NONE_DECLARED -> LOG.warn("Step {} ran but has no <rollback> for {}: cannot be undone", stepId,
                        context.platform().os());
                case ELEVATED -> LOG.warn("Step {} is elevated; its reverse commands would need elevation too (E12) "
                        + "and are not run", stepId);
                case UNTRUSTED -> LOG.warn("Step {}: the manifest is not trusted, its reverse commands are not run",
                        stepId);
            }
            stepsWithoutReverse.add(stepId);
            progress(stepId);
            return;
        }
        try {
            Undo.reverseStep(context, step);
            restored++;
        } catch (RuntimeException e) {
            failure("reverse of step " + stepId, e);
        }
        progress(stepId);
    }

    // --- files -------------------------------------------------------------

    private void restoreMode(InstallationRecord record, ModeChanged entry) {
        Path path = record.resolve(entry.path());
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(path, PosixModes.permissions(entry.mode()));
                restored++;
            }
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            failure("mode of " + path, e);
        }
        progress(entry.path());
    }

    private void restoreFile(InstallationRecord record, ReplacedFile entry) {
        Path path = record.resolve(entry.path());
        Path saved = record.resolve(entry.backup());
        try {
            if (!Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("backup " + saved + " is missing");
            }
            backup.restore(saved, path);
            restored++;
        } catch (IOException e) {
            failure("restore of " + path, e);
        }
        progress(entry.path());
    }

    private void deleteFile(InstallationRecord record, CreatedFile entry) {
        Path path = record.resolve(entry.path());
        try {
            if (Undo.deletePath(path)) {
                deleted++;
                removedFiles.add(path);
            }
        } catch (IOException e) {
            failure("delete of " + path, e);
        }
        progress(entry.path());
    }

    private void deleteDirectory(InstallationRecord record, CreatedDirectory entry) {
        deleteIfEmpty(record.resolve(entry.path()), entry.path());
        progress(entry.path());
    }

    private void deleteIfEmpty(Path dir, String what) {
        try {
            if (Undo.deleteIfEmpty(dir)) {
                deleted++;
            }
        } catch (IOException e) {
            failure("delete of " + what, e);
        }
    }

    private void restorePreviousRecord(Path copy) {
        Path file = RecordWriter.defaultFile(context.destination());
        try {
            Files.createDirectories(file.getParent());
            Files.move(copy, file, StandardCopyOption.REPLACE_EXISTING);
            restored++;
            LOG.info("Restored the previous installation's record {}", file);
        } catch (IOException e) {
            failure("restore of " + file, e);
        }
        progress(file.toString());
    }

    // --- bookkeeping -------------------------------------------------------

    private void failure(String what, Exception e) {
        String line = what + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        LOG.error("Rollback: {}", line, e);
        failures.add(line);
    }

    private void progress(String what) {
        done++;
        context.listener().rollbackProgress(done, total, what);
    }
}
