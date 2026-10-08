package de.yawi.installer.ui;

import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The uninstall as a JavaFX {@link Task}, the {@link InstallTask}'s
 * small sibling: takes the record and the two answers from the model on the
 * JavaFX thread, runs the {@link Uninstaller} on the worker and brings its
 * callbacks over coalesced. Cancel goes through the token; the task ends
 * normally with a cancelled {@link Uninstaller.Report} - nothing is rolled
 * back, what was removed stays removed.
 */
public final class UninstallTask extends Task<Uninstaller.Report> {

    private static final Logger LOG = LoggerFactory.getLogger(UninstallTask.class);

    private final InstallerModel model;
    private final CancellationToken token = new CancellationToken();
    private final InstallManifest manifest;
    private final InstallationRecord record;
    private final Optional<InstallationRegistry> registry;
    private final Uninstaller.Options options;

    private final IntegerProperty done = new SimpleIntegerProperty(this, "done", 0);
    private final IntegerProperty total = new SimpleIntegerProperty(this, "total", 0);
    private final StringProperty status = new SimpleStringProperty(this, "status", "");
    private final ObservableList<String> log = FXCollections.observableArrayList();
    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private volatile Instant startedAt;

    /** @param record the installation to remove, from the maintenance page's choice */
    public UninstallTask(InstallerModel model, InstallationRecord record) {
        this.model = model;
        this.manifest = model.getManifest();
        this.record = record;
        this.registry = model.getRegistry();
        this.options = model.uninstallOptions();
        model.onShutdown(() -> cancelAndAwait(Duration.ofSeconds(5)));
    }

    public ReadOnlyIntegerProperty doneProperty() {
        return done;
    }

    public ReadOnlyIntegerProperty totalProperty() {
        return total;
    }

    public ReadOnlyStringProperty statusProperty() {
        return status;
    }

    /** The output lines, newest last, capped like the install task's. JavaFX thread only. */
    public ObservableList<String> log() {
        return log;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @Override
    protected Uninstaller.Report call() {
        startedAt = Instant.now();
        // Elevate when the destination needs rights, like the install task.
        return new Uninstaller(manifest, model.getPlatform(), record, registry, Optional.of(model.getElevation()))
                .run(options, new Listener(), token);
    }

    /** Stops at the next item through the token; the task ends normally with a cancelled report. */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) {
            return false;
        }
        token.cancel();
        return true;
    }

    public void cancelAndAwait(Duration timeout) {
        if (isDone()) {
            return;
        }
        cancel(true);
        try {
            get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.CancellationException | ExecutionException | TimeoutException e) {
            LOG.debug("Uninstall task ended after cancel: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onFx(Runnable work) {
        pending.add(work);
        if (drainScheduled.compareAndSet(false, true)) {
            try {
                javafx.application.Platform.runLater(() -> {
                    drainScheduled.set(false);
                    Runnable next;
                    while ((next = pending.poll()) != null) {
                        next.run();
                    }
                });
            } catch (IllegalStateException e) {
                drainScheduled.set(false);
                pending.clear();
            }
        }
    }

    private final class Listener implements ProgressListener {

        @Override
        public void uninstallStarted(int total) {
            onFx(() -> {
                UninstallTask.this.total.set(total);
                done.set(0);
                status.set("");
            });
            updateProgress(0, Math.max(total, 1));
        }

        @Override
        public void uninstallProgress(int done, int total, String what) {
            onFx(() -> {
                UninstallTask.this.done.set(done);
                UninstallTask.this.total.set(total);
                status.set(what == null ? "" : what);
            });
            updateProgress(done, Math.max(total, 1));
        }

        @Override
        public void output(String line) {
            onFx(() -> {
                if (log.size() >= InstallTask.LOG_LIMIT) {
                    log.remove(0, log.size() - InstallTask.LOG_LIMIT + 1);
                }
                log.add(line);
            });
        }

        @Override
        public void uninstallFinished(Uninstaller.Report report) {
            report.keptModified().forEach(p -> output(model.i18n().get("cli.uninstall.kept.modified", p)));
            report.keptUnrecorded().forEach(p -> output(model.i18n().get("cli.uninstall.kept.userData", p)));
            if (!report.stepsWithoutReverse().isEmpty()) {
                output(model.i18n().get("cli.uninstall.noReverse", String.join(", ", report.stepsWithoutReverse())));
            }
            if (!report.clean() && !report.cancelled()) {
                output(model.i18n().get("cli.uninstall.failed", report.deleted(), report.failures().size(),
                        LogSetup.currentLogFile()));
            }
            report.failures().forEach(this::output);
        }
    }
}
