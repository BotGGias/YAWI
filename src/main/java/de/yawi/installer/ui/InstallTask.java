package de.yawi.installer.ui;

import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.download.SourceResolver;
import de.yawi.installer.core.download.torrent.FallbackPrompt;
import de.yawi.installer.core.download.torrent.NoPeersException;
import de.yawi.installer.core.download.torrent.TorrentRuntime;
import de.yawi.installer.core.manifest.Source;
import de.yawi.installer.core.engine.CancellationToken;
import de.yawi.installer.core.engine.ComponentRemoval;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.engine.InstallRunner;
import de.yawi.installer.core.engine.ProgressListener;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.engine.StepOutcome;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.state.ExistingInstallation;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * The installation as a JavaFX {@link Task}: snapshots the
 * model, runs the {@link InstallRunner} on the worker thread and brings its
 * callbacks onto the JavaFX thread - coalesced, so a step printing ten
 * thousand lines does not schedule ten thousand runnables.
 *
 * <p>Progress: {@link #progressProperty()} is the overall fraction,
 * {@link #stepProgressProperty()} the current step's (-1 = indeterminate),
 * {@link #stepIndexProperty()}/{@link #stepIdProperty()} the current step,
 * {@link #statusProperty()} what it is doing, {@link #log()} the last
 * {@link #LOG_LIMIT} output lines.
 */
public final class InstallTask extends Task<Engine.Result> {

    private static final Logger LOG = LoggerFactory.getLogger(InstallTask.class);

    /** Lines kept in {@link #log()}; older ones fall off the top. */
    public static final int LOG_LIMIT = 5000;
    /** Share of the overall bar the download phase takes when there is one. */
    static final double DOWNLOAD_SHARE = InstallRunner.DOWNLOAD_SHARE;

    public enum Phase { DOWNLOAD, STEPS, ROLLBACK }

    private final InstallerModel model;
    private final CancellationToken token = new CancellationToken();
    private final FailurePrompt prompt;
    /** Snapshots taken on the JavaFX thread; the worker never touches the model's observables. */
    private final Path destination;
    private final Set<String> selected;
    private final Map<String, String> inputs;
    private final Map<String, ProviderKind> choices;
    private final SourceResolver resolver;
    /** Snapshotted in the constructor like the rest; absent in tests. */
    private final Optional<InstallationRegistry> registry;
    /** The run's mode; {@code MODIFY} takes the deselected components back afterwards. */
    private final InstallerModel.InstallMode installMode;
    /** The installation being changed, for {@code MODIFY}; empty for a fresh install. */
    private final Optional<InstallationRecord> previousRecord;

    private final IntegerProperty stepIndex = new SimpleIntegerProperty(this, "stepIndex", -1);
    private final IntegerProperty stepCount = new SimpleIntegerProperty(this, "stepCount", 0);
    private final StringProperty stepId = new SimpleStringProperty(this, "stepId", null);
    private final ObjectProperty<Number> stepProgress = new SimpleObjectProperty<>(this, "stepProgress", -1);
    private final StringProperty status = new SimpleStringProperty(this, "status", "");
    /** True from the rights prompt until the next step starts. */
    private final javafx.beans.property.BooleanProperty elevating =
            new javafx.beans.property.SimpleBooleanProperty(this, "elevating", false);
    private final ObservableList<String> log = FXCollections.observableArrayList();
    private final ObjectProperty<Phase> phase = new SimpleObjectProperty<>(this, "phase", Phase.STEPS);
    private final StringProperty downloadSourceId = new SimpleStringProperty(this, "downloadSourceId", null);
    private final IntegerProperty downloadIndex = new SimpleIntegerProperty(this, "downloadIndex", -1);
    private final IntegerProperty downloadCount = new SimpleIntegerProperty(this, "downloadCount", 0);
    private final ObjectProperty<DownloadProgress> downloadProgress = new SimpleObjectProperty<>(this, "downloadProgress");
    private final BooleanProperty verifying = new SimpleBooleanProperty(this, "verifying", false);
    private final IntegerProperty rollbackDone = new SimpleIntegerProperty(this, "rollbackDone", 0);
    private final IntegerProperty rollbackTotal = new SimpleIntegerProperty(this, "rollbackTotal", 0);
    private volatile Thread worker;
    private final AtomicBoolean interrupted = new AtomicBoolean();

    private final ConcurrentLinkedQueue<Runnable> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private volatile Instant startedAt;

    /**
     * @param prompt what to do when a step with {@code onFailure="ask"}
     *               fails; called on the worker thread, expected to block
     *               until the user answered (see {@link #dialogPrompt})
     */
    public InstallTask(InstallerModel model, FailurePrompt prompt) {
        this(model, prompt, FallbackPrompt.ALWAYS);
    }

    /**
     * @param fallback asked before a torrent with {@code fallback="ask"} is
     *                 given up for the next provider (E08-S03); on the worker
     *                 thread, see {@link #dialogFallback}
     */
    public InstallTask(InstallerModel model, FailurePrompt prompt, FallbackPrompt fallback) {
        this(model, prompt, model.getSourceResolver().orElseGet(SourceResolver::forRuntime).withFallbackPrompt(fallback));
    }

    /** @param resolver how sources are obtained; tests inject one with a tame downloader */
    public InstallTask(InstallerModel model, FailurePrompt prompt, SourceResolver resolver) {
        this.model = model;
        this.prompt = prompt == null ? FailurePrompt.ABORT_ALWAYS : prompt;
        this.resolver = resolver;
        this.registry = model.getRegistry();
        InstallManifest manifest = model.getManifest();
        this.destination = model.getEffectiveDestination();
        this.selected = manifest.resolveSelection(model.getSelectedComponents());
        this.inputs = InstallRunner.selectedInputs(manifest, selected, model.getInputs());
        Map<String, ProviderKind> chosen = new LinkedHashMap<>();
        manifest.sourcesFor(selected, model.getPlatform().os()).forEach(s -> chosen.put(s.id(), model.sourceChoice(s)));
        this.choices = chosen;
        // Snapshot the maintenance choice on the JavaFX thread; MODIFY reverses dropped components afterwards.
        this.installMode = model.getInstallMode();
        this.previousRecord = model.getExistingInstallation().flatMap(ExistingInstallation::record);
        // Hooks run in reverse order: the task is cancelled first, then bt is stopped (E08-S02).
        model.onShutdown(TorrentRuntime::shutdownShared);
        model.onShutdown(() -> cancelAndAwait(Duration.ofSeconds(5)));
    }

    public ReadOnlyIntegerProperty stepIndexProperty() {
        return stepIndex;
    }

    public ReadOnlyIntegerProperty stepCountProperty() {
        return stepCount;
    }

    public ReadOnlyStringProperty stepIdProperty() {
        return stepId;
    }

    /** 0..1, or -1 while the step cannot say. */
    public ReadOnlyObjectProperty<Number> stepProgressProperty() {
        return stepProgress;
    }

    public ReadOnlyStringProperty statusProperty() {
        return status;
    }

    /** True while the system's rights prompt is up; the page shows a hint instead of a step. */
    public javafx.beans.property.ReadOnlyBooleanProperty elevatingProperty() {
        return elevating;
    }

    public ReadOnlyObjectProperty<Phase> phaseProperty() {
        return phase;
    }

    public ReadOnlyStringProperty downloadSourceIdProperty() {
        return downloadSourceId;
    }

    public ReadOnlyIntegerProperty downloadIndexProperty() {
        return downloadIndex;
    }

    public ReadOnlyIntegerProperty downloadCountProperty() {
        return downloadCount;
    }

    /** The latest snapshot of the running download, null before the first. */
    public ReadOnlyObjectProperty<DownloadProgress> downloadProgressProperty() {
        return downloadProgress;
    }

    /** True while bundled data is read for its checksum; the download properties describe it. */
    public ReadOnlyBooleanProperty verifyingProperty() {
        return verifying;
    }

    /** The output lines, newest last, capped at {@link #LOG_LIMIT}. JavaFX thread only. */
    public ObservableList<String> log() {
        return log;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public ReadOnlyIntegerProperty rollbackDoneProperty() {
        return rollbackDone;
    }

    public ReadOnlyIntegerProperty rollbackTotalProperty() {
        return rollbackTotal;
    }

    @Override
    protected Engine.Result call() throws Exception {
        startedAt = Instant.now();
        worker = Thread.currentThread();
        InstallRunner.Request request = new InstallRunner.Request(model.getManifest(), model.getPlatform(), destination,
                selected, inputs, choices, model.enabledAssociations(), model.isAddPathEntries(), Optional.empty());
        Listener listener = new Listener();
        Engine.Result result = new InstallRunner(resolver, registry, model.getElevation())
                .run(request, listener, prompt, token);
        if (result.succeeded() && installMode == InstallerModel.InstallMode.MODIFY && previousRecord.isPresent()) {
            removeDeselectedComponents(listener);
        }
        return result;
    }

    /**
     * After a successful modify: the run installed the new selection
     * and rewrote the record; here the steps of the components the user dropped
     * are taken back from the previous record. Best effort - a failure is a log
     * line, never a failed run.
     */
    private void removeDeselectedComponents(ProgressListener listener) {
        InstallManifest manifest = model.getManifest();
        Set<String> oldResolved = manifest.resolveSelection(previousRecord.get().components());
        Set<String> removed = ComponentRemoval.removedStepIds(manifest, model.getPlatform(), oldResolved, selected);
        if (removed.isEmpty()) {
            return;
        }
        LOG.info("Modify: removing {} step(s) of deselected components", removed.size());
        new ComponentRemoval(manifest, model.getPlatform(), previousRecord.get()).run(removed, listener, token);
    }

    /**
     * Stops the installation through its token and, once, the worker's
     * interrupt (a blocked download only notices that). The task does
     * <em>not</em> enter the CANCELLED state: it ends normally with a
     * cancelled {@link Engine.Result} once the rollback is through ,
     * so the page sees the rollback and its report. A second call never
     * interrupts again - that would hit the rollback.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (isDone()) {
            return false;
        }
        token.cancel();
        Thread thread = worker;
        if (mayInterruptIfRunning && thread != null && interrupted.compareAndSet(false, true)) {
            thread.interrupt();
        }
        return true;
    }

    /** Cancels and waits (bounded) for the engine to stop, so the record file is closed before the window goes. */
    public void cancelAndAwait(Duration timeout) {
        if (isDone()) {
            return;
        }
        cancel(true);
        try {
            get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.CancellationException | ExecutionException | TimeoutException e) {
            // The rollback may still be running on its (non-daemon) thread; the JVM waits for it.
            LOG.debug("Install task ended after cancel: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- callbacks onto the JavaFX thread ---------------------------------------

    /** Queues UI work and makes sure one runnable drains the queue on the JavaFX thread. */
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
                // The toolkit is gone (window closed during the rollback); nobody is watching any more.
                drainScheduled.set(false);
                pending.clear();
            }
        }
    }

    private final class Listener implements ProgressListener {

        @Override
        public void downloadStarted(Source source, int index, int count) {
            onFx(() -> {
                phase.set(Phase.DOWNLOAD);
                downloadSourceId.set(source.id());
                downloadIndex.set(index);
                downloadCount.set(count);
                downloadProgress.set(null);
                verifying.set(false);
            });
        }

        @Override
        public void downloadVerifying(Source source) {
            // Bound labels re-evaluate on every change: the phase flips last, so no "downloading" shows in between.
            onFx(() -> {
                verifying.set(true);
                downloadSourceId.set(source.id());
                downloadProgress.set(null);
                phase.set(Phase.DOWNLOAD);
            });
        }

        @Override
        public void downloadProgress(Source source, DownloadProgress progress) {
            onFx(() -> downloadProgress.set(progress));
        }

        @Override
        public void downloadFinished(Source source) {
            // A verified bundled source leaves no download behind to show; the id
            // goes first, or the label briefly says "downloading" in between.
            boolean download = choices.get(source.id()) != ProviderKind.BUNDLED;
            onFx(() -> {
                if (!download) {
                    downloadSourceId.set(null);
                }
                verifying.set(false);
            });
        }

        @Override
        public void elevationRequested() {
            onFx(() -> {
                phase.set(Phase.STEPS);
                stepProgress.set(-1);
                status.set("");
                elevating.set(true);
            });
        }

        @Override
        public void stepStarted(InstallStep step, int index, int count) {
            onFx(() -> {
                phase.set(Phase.STEPS);
                elevating.set(false);
                stepIndex.set(index);
                stepCount.set(count);
                stepId.set(step.id());
                stepProgress.set(-1);
                status.set("");
            });
        }

        @Override
        public void stepProgress(double fraction, String message) {
            onFx(() -> {
                stepProgress.set(fraction);
                if (message != null) {
                    status.set(message);
                }
            });
        }

        @Override
        public void stepFinished(InstallStep step, StepOutcome outcome) {
            onFx(() -> stepProgress.set(outcome == StepOutcome.DONE ? 1 : -1));
        }

        @Override
        public void overall(double fraction) {
            updateProgress(fraction, 1);
        }

        @Override
        public void downloadFallback(Source source, ProviderKind from, ProviderKind to, String reason) {
            // One localized line in the log: "game-data: BitTorrent found no peers within 90s, trying download".
            output(model.i18n().get("progress.fallback", source.id(), kindName(from), kindName(to), reason));
        }

        private String kindName(ProviderKind kind) {
            return model.i18n().get("summary.source." + kind.name().toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public void output(String line) {
            onFx(() -> {
                if (log.size() >= LOG_LIMIT) {
                    log.remove(0, log.size() - LOG_LIMIT + 1);
                }
                log.add(line);
            });
        }

        @Override
        public void rollbackStarted(int total) {
            onFx(() -> {
                phase.set(Phase.ROLLBACK);
                elevating.set(false);
                rollbackTotal.set(total);
                rollbackDone.set(0);
                stepProgress.set(total == 0 ? 1 : 0);
                status.set("");
            });
        }

        @Override
        public void rollbackProgress(int done, int total, String what) {
            onFx(() -> {
                rollbackDone.set(done);
                rollbackTotal.set(total);
                stepProgress.set(total == 0 ? 1 : (double) done / total);
                status.set(what == null ? "" : what);
            });
        }

        @Override
        public void rollbackFinished(Rollback.Report report) {
            output(model.i18n().get(report.clean() ? "cli.rollback.done" : "cli.rollback.failed",
                    report.restored(), report.deleted(), report.failures().size(), LogSetup.currentLogFile()));
            report.failures().forEach(Listener.this::output);
        }
    }

    /** Like {@link #dialogPrompt}, for the torrent fallback question. */
    public static FallbackPrompt dialogFallback(Function<NoPeersException, FallbackPrompt.Decision> show) {
        return (source, torrent, cause) -> {
            CompletableFuture<FallbackPrompt.Decision> answer = new CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    answer.complete(show.apply(cause));
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                }
            });
            try {
                return answer.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FallbackPrompt.Decision.ABORT;
            } catch (ExecutionException e) {
                LOG.warn("Fallback dialog failed, aborting", e.getCause());
                return FallbackPrompt.Decision.ABORT;
            }
        };
    }

    /**
     * A {@link FailurePrompt} that asks on the JavaFX thread and blocks the
     * worker until the user answered. {@code show} receives the failure and
     * returns the decision; the page supplies the dialog.
     */
    public static FailurePrompt dialogPrompt(Function<StepFailedException, FailurePrompt.Decision> show) {
        return failure -> {
            CompletableFuture<FailurePrompt.Decision> answer = new CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    answer.complete(show.apply(failure));
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                }
            });
            try {
                return answer.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FailurePrompt.Decision.ABORT;
            } catch (ExecutionException e) {
                LOG.warn("Failure prompt failed, aborting", e.getCause());
                return FailurePrompt.Decision.ABORT;
            }
        };
    }

    /** Lines the user should see once for a finished run: continued failures. */
    public static List<String> continuedFailureIds(Engine.Result result) {
        List<String> ids = new ArrayList<>();
        result.continuedFailures().forEach(r -> ids.add(r.step().id()));
        return ids;
    }
}
