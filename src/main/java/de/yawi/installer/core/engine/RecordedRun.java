package de.yawi.installer.core.engine;

import de.yawi.installer.core.error.Errors;
import de.yawi.installer.core.state.RecordWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The step phase of an installation with everything around it:
 * the record of a previous installation is kept aside, the record
 * is opened, the {@link Engine} runs, and afterwards either the backup goes
 * (success) or the {@link Rollback} runs. Shared by {@link InstallRunner}
 * and the elevated process (E12-S03), which owns the record when the whole
 * run is elevated.
 */
public final class RecordedRun {

    private static final Logger LOG = LoggerFactory.getLogger(RecordedRun.class);

    private RecordedRun() {
    }

    /**
     * A second segment that runs after the plan's steps succeeded, while the
     * record is still open (the elevated block of a partial run).
     * Its entries reach the file through the writer; a failure means the
     * segment has undone itself, the rollback here undoes the plan's steps.
     */
    @FunctionalInterface
    public interface Segment {
        Engine.Result run(RecordWriter writer);
    }

    public static Engine.Result execute(ExecutionPlan plan, ExecutionContext context, ProgressListener out) {
        return execute(plan, context, out, null);
    }

    /**
     * Runs {@code plan} against {@code context}, writing the record under the
     * destination. Never throws for a failed installation; it does throw for
     * a record that cannot be opened (nothing has been written then).
     *
     * @param out    the listener of the rollback (the engine uses the context's)
     * @param second an elevated segment to run after the plan's steps, or null
     */
    public static Engine.Result execute(ExecutionPlan plan, ExecutionContext context, ProgressListener out,
                                        Segment second) {
        Path destination = context.destination();
        Backup backup = Backup.of(context);
        boolean destinationExisted = Files.isDirectory(destination);
        Path recordFile = RecordWriter.defaultFile(destination);
        Optional<Path> previousRecord = Optional.empty();
        Engine.Result result;
        try {
            previousRecord = keepPreviousRecord(recordFile, backup);
            try (RecordWriter writer = RecordWriter.open(recordFile, context.record())) {
                result = new Engine().run(plan, context);
                if (result.succeeded() && second != null) {
                    result = merge(result, second.run(writer));
                }
                if (result.succeeded()) {
                    // The last entry: from here on a newer modification time means "changed by the user" (E14-S03).
                    context.record().runFinished(Instant.now());
                }
            }
        } catch (IOException e) {
            // The record could not even be started: nothing has been written yet.
            discardQuietly(backup);
            throw Errors.fromIo(e, destination);
        }
        if (result.succeeded()) {
            discardQuietly(backup);
            return result;
        }
        // Undo what the run did (also after a cancellation). A second segment has undone itself already;
        // both reports become one.
        Rollback rollback = new Rollback(context.forRollback(out, new CancellationToken()), plan, backup,
                previousRecord, !destinationExisted);
        Rollback.Report own = rollback.run();
        return result.withRollback(result.rollback().map(r -> r.merge(own)).orElse(own));
    }

    /** The two segments as one result: reports in order, the failure and rollback of the second. */
    static Engine.Result merge(Engine.Result first, Engine.Result second) {
        List<Engine.StepReport> reports = new ArrayList<>(first.reports());
        reports.addAll(second.reports());
        Engine.Result merged = new Engine.Result(second.succeeded(), reports, second.failure());
        return second.rollback().map(merged::withRollback).orElse(merged);
    }

    private static Optional<Path> keepPreviousRecord(Path recordFile, Backup backup) throws IOException {
        if (!Files.isRegularFile(recordFile)) {
            return Optional.empty();
        }
        Path copy = backup.root().resolve(RecordWriter.DIRECTORY).resolve(RecordWriter.FILE_NAME);
        Files.createDirectories(copy.getParent());
        Files.copy(recordFile, copy, StandardCopyOption.REPLACE_EXISTING);
        return Optional.of(copy);
    }

    static void discardQuietly(Backup backup) {
        try {
            backup.discard();
        } catch (IOException e) {
            LOG.warn("Could not remove the backup folder {}: {}", backup.root(), e.getMessage());
        }
    }
}
