package de.yawi.installer.core.elevation;

import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.state.InstallationRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The child's side of {@code events.log}: every progress event and (for a
 * partial run) every record entry as one {@link EventLine}, flushed at once
 * so the parent sees it while it happens.
 */
final class EventSink implements ProgressListener, AutoCloseable {

    private final BufferedWriter out;

    EventSink(Path file) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
    }

    synchronized void emit(String line) {
        try {
            out.write(line);
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write event", e);
        }
    }

    void ready() {
        emit(EventLine.encode(EventLine.READY));
    }

    void record(InstallationRecord.Entry entry) {
        emit(EventLine.encodeRecord(entry));
    }

    @Override
    public void stepStarted(InstallStep step, int index, int count) {
        emit(EventLine.encode(EventLine.STEP_STARTED, step.id(), Integer.toString(index), Integer.toString(count)));
    }

    @Override
    public void stepProgress(double fraction, String message) {
        emit(EventLine.encode(EventLine.STEP_PROGRESS, Double.toString(fraction), message == null ? "" : message,
                message == null ? "0" : "1"));
    }

    @Override
    public void stepFinished(InstallStep step, StepOutcome outcome) {
        emit(EventLine.encode(EventLine.STEP_FINISHED, step.id(), outcome.name()));
    }

    @Override
    public void overall(double fraction) {
        emit(EventLine.encode(EventLine.OVERALL, Double.toString(fraction)));
    }

    @Override
    public void output(String line) {
        emit(EventLine.encode(EventLine.OUTPUT, line));
    }

    @Override
    public void rollbackStarted(int total) {
        emit(EventLine.encode(EventLine.ROLLBACK_STARTED, Integer.toString(total)));
    }

    @Override
    public void rollbackProgress(int done, int total, String what) {
        emit(EventLine.encode(EventLine.ROLLBACK_PROGRESS, Integer.toString(done), Integer.toString(total),
                what == null ? "" : what));
    }

    @Override
    public void rollbackFinished(Rollback.Report report) {
        emit(EventLine.encode(EventLine.ROLLBACK_FINISHED, Integer.toString(report.restored()),
                Integer.toString(report.deleted()), EventLine.list(report.stepsWithoutReverse()),
                EventLine.list(report.failures()),
                EventLine.list(report.leftBehind().stream().map(Path::toString).toList())));
    }

    @Override
    public void uninstallStarted(int total) {
        emit(EventLine.encode(EventLine.UNINSTALL_STARTED, Integer.toString(total)));
    }

    @Override
    public void uninstallProgress(int done, int total, String what) {
        emit(EventLine.encode(EventLine.UNINSTALL_PROGRESS, Integer.toString(done), Integer.toString(total),
                what == null ? "" : what));
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    /** For tests: the lines of an events file, decoded. */
    static List<List<String>> readAll(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(EventLine::decode).flatMap(java.util.Optional::stream).toList();
    }
}
