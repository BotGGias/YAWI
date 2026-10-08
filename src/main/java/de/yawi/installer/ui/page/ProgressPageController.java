package de.yawi.installer.ui.page;

import de.yawi.installer.core.download.DownloadProgress;
import de.yawi.installer.core.download.Formats;
import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.Rollback;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.manifest.ByteSize;
import de.yawi.installer.core.manifest.InstallStep;
import de.yawi.installer.core.download.torrent.FallbackPrompt;
import de.yawi.installer.core.download.torrent.NoPeersException;
import de.yawi.installer.core.engine.FailurePrompt;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.StepFailedException;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.error.CancelledException;
import de.yawi.installer.core.error.UninstallFailedException;
import de.yawi.installer.core.state.ExistingInstallation;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.ui.InstallTask;
import de.yawi.installer.ui.InstallerModel;
import de.yawi.installer.ui.UninstallTask;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TitledPane;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Progress page: starts the {@link InstallTask} when entered,
 * shows overall and step progress, the current file, elapsed time and the
 * live output; keeps "Next" locked until the task ends and then reports the
 * outcome on the page - never as a stack trace, never as a dialog that
 * blocks the way to the finish page.
 */
public class ProgressPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressPageController.class);

    @FXML
    private Label hint;
    @FXML
    private Label stepLabel;
    @FXML
    private ProgressBar overallProgress;
    @FXML
    private ProgressBar stepProgress;
    @FXML
    private Label status;
    @FXML
    private Label elapsed;
    @FXML
    private Label outcome;
    @FXML
    private Label outcomeHint;
    @FXML
    private TitledPane details;
    @FXML
    private ListView<String> log;

    private final BooleanProperty done = new SimpleBooleanProperty(this, "done", false);
    private final CompletableFuture<Engine.Result> result = new CompletableFuture<>();
    private final Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tick()));
    private InstallTask task;
    private UninstallTask uninstallTask;

    public ProgressPageController(InstallerModel model) {
        super(model);
        clock.setCycleCount(Animation.INDEFINITE);
    }

    @Override
    public ObservableBooleanValue nextAllowed() {
        return done;
    }

    /** Once the installation starts there is no way back. */
    @Override
    public boolean locksBack() {
        return true;
    }

    /** Completes on the JavaFX thread when the task ended and the page shows the outcome; for tests and E12. */
    public CompletableFuture<Engine.Result> result() {
        return result;
    }

    /** "Uninstalling" instead of "Installing" while the page removes an installation. */
    @Override
    protected String titleKey(String pageName) {
        return uninstalling() ? "page.progress.uninstall.title" : super.titleKey(pageName);
    }

    @FXML
    private void initialize() {
        hint.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get(uninstalling() ? "progress.uninstall.hint" : "progress.hint",
                        model.getManifest().product().name()),
                model.localeProperty(), model.installModeProperty()));
        outcome.setVisible(false);
        outcome.managedProperty().bind(outcome.visibleProperty());
        outcomeHint.setVisible(false);
        outcomeHint.managedProperty().bind(outcomeHint.visibleProperty());
        stepLabel.setText("");
        elapsed.setText("");
    }

    /** Starts the installation exactly once; re-entering (impossible while Back is locked) does nothing. */
    @Override
    public void onEnter() {
        if (task != null || uninstallTask != null) {
            return;
        }
        if (uninstalling()) {
            startUninstall();
            return;
        }
        task = new InstallTask(model, InstallTask.dialogPrompt(this::askOnFailure),
                InstallTask.dialogFallback(this::askOnNoPeers));
        bindTo(task);
        task.setOnSucceeded(e -> finished(task.getValue()));
        task.setOnFailed(e -> failed(task.getException()));
        // Cancel goes through the task's token; it ends normally with a cancelled result after the rollback.
        model.onCancelRequest(() -> task.cancel(true));
        model.cancelLockedProperty().bind(task.phaseProperty().isEqualTo(InstallTask.Phase.ROLLBACK));
        Thread worker = new Thread(task, "install");
        // Not a daemon: a rollback outlives a closed window.
        worker.setDaemon(false);
        clock.play();
        worker.start();
    }

    // --- uninstall ---------------------------------------------------

    /** The same page, driven by an {@link UninstallTask}: one bar, the current item, the live log; cancel any time. */
    private void startUninstall() {
        InstallationRecord record = model.getExistingInstallation().flatMap(ExistingInstallation::record)
                .orElseThrow(() -> new IllegalStateException("uninstall without an installation record"));
        uninstallTask = new UninstallTask(model, record);
        overallProgress.progressProperty().bind(uninstallTask.progressProperty());
        stepProgress.progressProperty().bind(uninstallTask.progressProperty());
        status.textProperty().bind(uninstallTask.statusProperty());
        stepLabel.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get("progress.uninstall", uninstallTask.doneProperty().get(),
                        uninstallTask.totalProperty().get()),
                uninstallTask.doneProperty(), uninstallTask.totalProperty(), model.localeProperty()));
        log.setItems(uninstallTask.log());
        uninstallTask.log().addListener((javafx.collections.ListChangeListener<String>) change -> {
            if (!uninstallTask.log().isEmpty()) {
                log.scrollTo(uninstallTask.log().size() - 1);
            }
        });
        uninstallTask.setOnSucceeded(e -> uninstallFinished(uninstallTask.getValue()));
        uninstallTask.setOnFailed(e -> uninstallFailed(uninstallTask.getException()));
        model.onCancelRequest(() -> uninstallTask.cancel(true));
        Thread worker = new Thread(uninstallTask, "uninstall");
        worker.setDaemon(false);
        clock.play();
        worker.start();
    }

    private void uninstallFinished(Uninstaller.Report report) {
        clock.stop();
        tick();
        stepProgress.progressProperty().unbind();
        status.textProperty().unbind();
        status.setText("");
        model.onCancelRequest(null);
        model.setUninstallReport(report);
        if (report.cancelled()) {
            stepProgress.setProgress(0);
            show(model.i18n().text("progress.uninstall.cancelled"), null, true);
        } else if (report.clean()) {
            stepProgress.setProgress(1);
            int kept = report.keptModified().size() + report.keptUnrecorded().size();
            show(kept == 0 ? model.i18n().text("progress.uninstall.done")
                    : model.i18n().text("progress.uninstall.done.kept", kept), null, false);
        } else {
            stepProgress.setProgress(0);
            LOG.error("Uninstall incomplete: {} item(s) could not be removed", report.failures().size());
            show(model.i18n().text("progress.uninstall.failed", report.failures().size(), LogSetup.currentLogFile()),
                    model.i18n().text("error.uninstall.hint"), true);
        }
        done.set(true);
        result.complete(new Engine.Result(report.clean(), List.of(), report.clean() ? Optional.empty()
                : Optional.of(report.cancelled() ? new CancelledException()
                        : new UninstallFailedException(report.failures()))));
    }

    /** The task itself threw - the inventory or the reverse commands' context could not be built. */
    private void uninstallFailed(Throwable exception) {
        InstallerException failure = InstallerException.wrap(exception);
        LOG.error("Uninstall failed unexpectedly", exception);
        uninstallFinished(new Uninstaller.Report(0, 0, List.of(), List.of(), List.of(),
                List.of(failure.getMessage()), false, false));
    }

    private void bindTo(InstallTask task) {
        overallProgress.progressProperty().bind(task.progressProperty());
        // During the download phase the step bar and the status line show the download instead.
        stepProgress.progressProperty().bind(Bindings.createDoubleBinding(() -> {
            if (task.phaseProperty().get() == InstallTask.Phase.DOWNLOAD) {
                DownloadProgress p = task.downloadProgressProperty().get();
                return p == null ? -1.0 : p.fraction();
            }
            return task.stepProgressProperty().get().doubleValue();
        }, task.phaseProperty(), task.downloadProgressProperty(), task.stepProgressProperty()));
        // The rollback owns the step bar and the label; the overall bar stays where the run stopped.
        status.textProperty().bind(Bindings.createStringBinding(() -> {
            if (task.elevatingProperty().get()) {
                return model.i18n().get("progress.elevating");
            }
            if (task.phaseProperty().get() != InstallTask.Phase.DOWNLOAD) {
                return task.statusProperty().get();
            }
            DownloadProgress p = task.downloadProgressProperty().get();
            if (p == null) {
                return "";
            }
            Locale locale = model.getLocale();
            String done = ByteSize.format(p.bytes(), locale);
            String total = p.total() < 0 ? "?" : ByteSize.format(p.total(), locale);
            if (task.verifyingProperty().get()) {
                return model.i18n().get("progress.verify.status", done, total);
            }
            return model.i18n().get("progress.download.status", done, total,
                    Formats.speed(p.bytesPerSecond(), locale), Formats.duration(p.etaSeconds()));
        }, task.phaseProperty(), task.downloadProgressProperty(), task.verifyingProperty(), task.statusProperty(),
                task.elevatingProperty(), model.localeProperty()));
        log.setItems(task.log());
        // Newest line stays in view; the list is capped by the task.
        task.log().addListener((javafx.collections.ListChangeListener<String>) change -> {
            if (!task.log().isEmpty()) {
                log.scrollTo(task.log().size() - 1);
            }
        });
        stepLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            if (task.phaseProperty().get() == InstallTask.Phase.ROLLBACK) {
                return model.i18n().get("progress.rollback", task.rollbackDoneProperty().get(),
                        task.rollbackTotalProperty().get());
            }
            if (task.phaseProperty().get() == InstallTask.Phase.DOWNLOAD) {
                String source = task.downloadSourceIdProperty().get();
                if (source == null) {
                    return "";
                }
                if (task.verifyingProperty().get()) {
                    return model.i18n().get("progress.verify", source);
                }
                return model.i18n().get("progress.download", source,
                        task.downloadIndexProperty().get() + 1, task.downloadCountProperty().get());
            }
            String id = task.stepIdProperty().get();
            if (id == null) {
                return "";
            }
            return model.i18n().get("progress.step", task.stepIndexProperty().get() + 1,
                    task.stepCountProperty().get(), stepName(id));
        }, task.phaseProperty(), task.downloadSourceIdProperty(), task.downloadIndexProperty(), task.downloadCountProperty(),
                task.verifyingProperty(), task.stepIdProperty(), task.stepIndexProperty(), task.stepCountProperty(),
                task.rollbackDoneProperty(), task.rollbackTotalProperty(), model.localeProperty()));
    }

    /** The manifest's step name, or for an implicit shortcut / association step a generated one. */
    private String stepName(String stepId) {
        return InstallStep.Shortcut.shortcutIdOf(stepId)
                .flatMap(model.getManifest().integration()::shortcut)
                .map(s -> model.i18n().get("progress.shortcut", s.name()))
                .or(() -> InstallStep.FileAssociation.extensionOf(stepId)
                        .map(ext -> model.i18n().get("progress.association", ext)))
                .or(() -> InstallStep.PathEntries.isPathStep(stepId)
                        ? Optional.of(model.i18n().get("progress.pathEntry")) : Optional.empty())
                .orElseGet(() -> model.i18n().getManifestMessages().stepName(model.getLocale(), stepId));
    }

    private void tick() {
        Instant started = task != null ? task.startedAt() : uninstallTask != null ? uninstallTask.startedAt() : null;
        if (started == null) {
            return;
        }
        long seconds = java.time.Duration.between(started, Instant.now()).getSeconds();
        elapsed.textProperty().unbind();
        elapsed.textProperty().bind(model.i18n().text("progress.elapsed",
                String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)));
    }

    /** The {@code onFailure="ask"} dialog: continue or abort, on the JavaFX thread. */
    private FailurePrompt.Decision askOnFailure(StepFailedException failure) {
        Locale locale = model.getLocale();
        ButtonType go = new ButtonType(model.i18n().get("progress.ask.continue"), ButtonType.OK.getButtonData());
        ButtonType stop = new ButtonType(model.i18n().get("progress.ask.abort"), ButtonType.CANCEL.getButtonData());
        Alert alert = new Alert(Alert.AlertType.WARNING, failure.userMessage(model.i18n().getMessages(), locale)
                + "\n\n" + model.i18n().get("progress.ask.question"), go, stop);
        alert.setTitle(model.i18n().get("progress.ask.title"));
        alert.setHeaderText(null);
        alert.initOwner(status.getScene() == null ? null : status.getScene().getWindow());
        return alert.showAndWait().orElse(stop) == go ? FailurePrompt.Decision.CONTINUE : FailurePrompt.Decision.ABORT;
    }

    /** The {@code fallback="ask"} dialog: switch to the next provider or abort, on the JavaFX thread. */
    private FallbackPrompt.Decision askOnNoPeers(NoPeersException cause) {
        ButtonType go = new ButtonType(model.i18n().get("progress.torrent.ask.fallback"), ButtonType.OK.getButtonData());
        ButtonType stop = new ButtonType(model.i18n().get("progress.ask.abort"), ButtonType.CANCEL.getButtonData());
        Alert alert = new Alert(Alert.AlertType.WARNING, model.i18n().get("progress.torrent.ask.question",
                de.yawi.installer.core.manifest.Durations.format(cause.waited())), go, stop);
        alert.setTitle(model.i18n().get("progress.torrent.ask.title"));
        alert.setHeaderText(null);
        alert.initOwner(status.getScene() == null ? null : status.getScene().getWindow());
        return alert.showAndWait().orElse(stop) == go ? FallbackPrompt.Decision.FALLBACK : FallbackPrompt.Decision.ABORT;
    }

    private void finished(Engine.Result result) {
        clock.stop();
        tick();
        stepProgress.progressProperty().unbind();
        status.textProperty().unbind();
        status.setText("");
        model.onCancelRequest(null);
        model.cancelLockedProperty().unbind();
        model.cancelLockedProperty().set(false);
        model.setInstallResult(result);

        if (result.succeeded()) {
            stepProgress.setProgress(1);
            List<String> continued = InstallTask.continuedFailureIds(result);
            if (continued.isEmpty()) {
                show(model.i18n().text("progress.done"), null, false);
            } else {
                show(model.i18n().text("progress.continued", continued.size(), String.join(", ", continued)),
                        model.i18n().text("progress.continued.hint"), true);
            }
        } else if (result.isCancelled()) {
            stepProgress.setProgress(0);
            boolean clean = result.rollback().map(Rollback.Report::clean).orElse(true);
            show(model.i18n().text("progress.cancelled"), result.rollback().isPresent() ? rollbackNote(result) : null, !clean);
        } else {
            stepProgress.setProgress(0);
            InstallerException failure = result.failure().orElseThrow();
            LOG.error("Installation failed [{}]: {}", failure.code(), failure.getMessage());
            // Process output is already in the log; other details (manifest problems) are not.
            if (!(failure instanceof StepFailedException)) {
                failure.detail().ifPresent(detail -> detail.lines().forEach(task.log()::add));
            }
            show(Bindings.createStringBinding(
                            () -> failure.userMessage(model.i18n().getMessages(), model.getLocale()), model.localeProperty()),
                    Bindings.createStringBinding(() -> {
                        String hint = failure.hint(model.i18n().getMessages(), model.getLocale());
                        String note = rollbackNote(result).get();
                        return note.isEmpty() ? hint : hint + "\n" + note;
                    }, model.localeProperty()),
                    true);
        }
        done.set(true);
        this.result.complete(result);
    }

    /** What the rollback did, for the hint line under the outcome; empty when none ran. */
    private javafx.beans.value.ObservableStringValue rollbackNote(Engine.Result result) {
        return Bindings.createStringBinding(() -> result.rollback().map(report -> {
            String note = report.clean()
                    ? model.i18n().get("progress.rolledBack")
                    : model.i18n().get("progress.rollbackFailed", LogSetup.currentLogFile());
            if (!report.stepsWithoutReverse().isEmpty()) {
                note += "\n" + model.i18n().get("progress.rollback.noReverse", String.join(", ", report.stepsWithoutReverse()));
            }
            return note;
        }).orElse(""), model.localeProperty());
    }

    /** The task itself threw - the plan could not be built or the record not opened. */
    private void failed(Throwable exception) {
        InstallerException failure = InstallerException.wrap(exception);
        if (failure.code() == de.yawi.installer.core.error.ErrorCode.GENERAL) {
            LOG.error("Installation failed unexpectedly", exception);
        }
        finished(new Engine.Result(false, List.of(), Optional.of(failure)));
    }

    private void show(javafx.beans.value.ObservableStringValue text, javafx.beans.value.ObservableStringValue hintText,
                      boolean expandDetails) {
        outcome.textProperty().bind(text);
        outcome.getStyleClass().remove("warning");
        if (expandDetails) {
            outcome.getStyleClass().add("warning");
        }
        outcome.setVisible(true);
        if (hintText != null) {
            outcomeHint.textProperty().bind(hintText);
            outcomeHint.setVisible(true);
        }
        if (expandDetails) {
            details.setExpanded(true);
        }
    }
}
