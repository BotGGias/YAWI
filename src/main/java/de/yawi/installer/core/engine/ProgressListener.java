package de.yawi.installer.core.engine;

import de.yawi.installer.core.manifest.InstallStep;

/**
 * How the engine reports what it is doing. Called on the engine's thread;
 * the UI adapter is responsible for hopping onto the JavaFX thread and for
 * throttling.
 */
public interface ProgressListener {

    ProgressListener NOOP = new ProgressListener() {
    };

    /** A step is about to run; {@code index} is 0-based, {@code count} the number of planned steps. */
    default void stepStarted(InstallStep step, int index, int count) {
    }

    /**
     * Progress inside the current step.
     *
     * @param fraction 0..1, or {@code -1} if the step cannot tell (a running process)
     * @param message  what is being done right now, e.g. the file being written; may be null
     */
    default void stepProgress(double fraction, String message) {
    }

    default void stepFinished(InstallStep step, StepOutcome outcome) {
    }

    /** Overall progress 0..1 from the step weights. */
    default void overall(double fraction) {
    }

    /** One line for the detail log: process output, file names, notes. */
    default void output(String line) {
    }

    // --- download phase, before the steps ------------------------

    /** A source is being fetched; {@code index} 0-based among the {@code count} sources that need a download. */
    default void downloadStarted(de.yawi.installer.core.manifest.Source source, int index, int count) {
    }

    default void downloadProgress(de.yawi.installer.core.manifest.Source source,
                                  de.yawi.installer.core.download.DownloadProgress progress) {
    }

    default void downloadFinished(de.yawi.installer.core.manifest.Source source) {
    }

    /**
     * A source that is not downloaded is being read for its checksum
     *; {@link #downloadProgress} follows with the bytes read.
     * Downloads are verified while they are written and do not report this.
     */
    default void downloadVerifying(de.yawi.installer.core.manifest.Source source) {
    }

    /** One provider of the source failed, the next kind is tried (E08-S03) - e.g. no torrent peers, so HTTP. */
    default void downloadFallback(de.yawi.installer.core.manifest.Source source,
                                  de.yawi.installer.core.download.ProviderKind from,
                                  de.yawi.installer.core.download.ProviderKind to, String reason) {
    }

    // --- elevation ---------------------------------------------------

    /** The system's rights prompt is about to appear (or has appeared) for the elevated part of the run. */
    default void elevationRequested() {
    }

    // --- rollback, after a failure or cancellation ---------------

    /** The rollback begins; {@code total} is the number of things it will undo. */
    default void rollbackStarted(int total) {
    }

    /** One thing undone (or attempted); {@code what} names it. */
    default void rollbackProgress(int done, int total, String what) {
    }

    default void rollbackFinished(Rollback.Report report) {
    }

    // --- uninstall -----------------------------------------------

    /** The uninstall begins; {@code total} is the number of items it will handle. */
    default void uninstallStarted(int total) {
    }

    /** One item handled (removed, kept or attempted); {@code what} names it. */
    default void uninstallProgress(int done, int total, String what) {
    }

    default void uninstallFinished(Uninstaller.Report report) {
    }
}
