package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallStep;

/**
 * Forwards everything to {@code target}, but places one segment of a run into
 * the whole: step indices are shifted by {@code indexOffset} and reported
 * against {@code totalCount}, the segment's own overall fraction (0..1) is
 * squeezed into {@code [offset, offset + share]}. Used for the download
 * phase, and for the normal and the elevated segments of a plan.
 */
public record SegmentListener(ProgressListener target, int indexOffset, int totalCount, double offset, double share)
        implements ProgressListener {

    /** Only the fraction is rescaled; step numbering passes through. */
    public static SegmentListener fractionOnly(ProgressListener target, double offset, double share) {
        return new SegmentListener(target, 0, -1, offset, share);
    }

    @Override
    public void stepStarted(InstallStep step, int index, int count) {
        target.stepStarted(step, index + indexOffset, totalCount < 0 ? count : totalCount);
    }

    @Override
    public void stepProgress(double fraction, String message) {
        target.stepProgress(fraction, message);
    }

    @Override
    public void stepFinished(InstallStep step, StepOutcome outcome) {
        target.stepFinished(step, outcome);
    }

    @Override
    public void overall(double fraction) {
        target.overall(offset + fraction * share);
    }

    @Override
    public void output(String line) {
        target.output(line);
    }

    @Override
    public void elevationRequested() {
        target.elevationRequested();
    }

    @Override
    public void rollbackStarted(int total) {
        target.rollbackStarted(total);
    }

    @Override
    public void rollbackProgress(int done, int total, String what) {
        target.rollbackProgress(done, total, what);
    }

    @Override
    public void rollbackFinished(Rollback.Report report) {
        target.rollbackFinished(report);
    }

    @Override
    public void uninstallStarted(int total) {
        target.uninstallStarted(total);
    }

    @Override
    public void uninstallProgress(int done, int total, String what) {
        target.uninstallProgress(done, total, what);
    }

    @Override
    public void uninstallFinished(Uninstaller.Report report) {
        target.uninstallFinished(report);
    }
}
