package de.yawi.installer.ui.page;

import de.yawi.installer.ui.AnswerExport;
import de.yawi.installer.ui.Diagnostics;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Last page: success or failure, "launch the application" (acted on by E13),
 * "open log", "save report" and "save answers" ( the choices as an
 * answer file for a silent roll-out; useful after a failure too).
 */
public class FinishPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(FinishPageController.class);

    @FXML
    private Label message;
    @FXML
    private Label pathHint;
    @FXML
    private CheckBox launch;
    @FXML
    private Button openLog;
    @FXML
    private Button saveReport;
    @FXML
    private Button saveAnswers;
    @FXML
    private Label reportStatus;

    public FinishPageController(InstallerModel model) {
        super(model);
    }

    /** The message key for the finished run: by mode and by success. */
    private String outcomeKey() {
        if (uninstalling()) {
            return model.isInstallSucceeded() ? "finish.uninstalled" : "finish.uninstallFailed";
        }
        if (!model.isInstallSucceeded()) {
            return "finish.failure";
        }
        return switch (model.getInstallMode()) {
            case REPAIR -> "finish.repaired";
            case MODIFY -> "finish.modified";
            default -> "finish.success";
        };
    }

    @FXML
    private void initialize() {
        String product = model.getManifest().product().name();
        message.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get(outcomeKey(), product),
                model.installSucceededProperty(), model.installModeProperty(), model.localeProperty()));
        // The PATH hint: shown after a successful install that added PATH entries, not on uninstall.
        if (!model.getManifest().integration().pathEntries().isEmpty()) {
            pathHint.textProperty().bind(model.i18n().text("path.newSessionsHint"));
            pathHint.managedProperty().bind(pathHint.visibleProperty());
            pathHint.visibleProperty().bind(model.installSucceededProperty().and(model.addPathEntriesProperty())
                    .and(model.installModeProperty().isNotEqualTo(InstallerModel.InstallMode.UNINSTALL)));
        } else {
            pathHint.setManaged(false);
            pathHint.setVisible(false);
        }
        launch.textProperty().bind(model.i18n().text("finish.launch", product));
        launch.selectedProperty().bindBidirectional(model.launchAfterFinishProperty());
        // Nothing to launch after a failed installation.
        launch.disableProperty().bind(model.installSucceededProperty().not());
        // Nothing to launch and no answers worth saving after an uninstall.
        for (javafx.scene.Node node : new javafx.scene.Node[] {launch, saveAnswers}) {
            node.managedProperty().bind(node.visibleProperty());
            node.visibleProperty().bind(model.installModeProperty().isNotEqualTo(InstallerModel.InstallMode.UNINSTALL));
        }
        // ... and nothing to launch without a <shortcut> in the manifest.
        if (model.getManifest().integration().shortcuts().isEmpty()) {
            launch.visibleProperty().unbind();
            launch.setVisible(false);
            launch.setSelected(false);
        }
    }

    @Override
    public NextAction nextAction() {
        return NextAction.FINISH;
    }

    @Override
    public boolean locksBack() {
        return true;
    }

    @FXML
    private void openLog() {
        Diagnostics.openLog(model.getHostServices());
    }

    /** Builds the report from the model and asks where to put it; the result is shown on the page. */
    @FXML
    private void saveReport() {
        reportStatus.textProperty().unbind();
        try {
            Optional<Path> saved = Diagnostics.saveReport(saveReport.getScene().getWindow(), model.getPlatform(),
                    model.i18n().get("report.chooserTitle"), Diagnostics.report(model, Optional.empty()));
            saved.ifPresent(file -> reportStatus.textProperty().bind(model.i18n().text("report.saved", file.toString())));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not save the diagnostic report", e);
            reportStatus.textProperty().bind(model.i18n().text("report.failed", e.getMessage()));
        }
    }

    /** Writes the wizard's choices as an answer file; the result is shown on the page like the report's. */
    @FXML
    private void saveAnswers() {
        reportStatus.textProperty().unbind();
        try {
            Optional<Path> saved = AnswerExport.save(saveAnswers.getScene().getWindow(), model,
                    model.i18n().get("answers.chooserTitle"));
            saved.ifPresent(file -> reportStatus.textProperty().bind(model.i18n().text("answers.saved", file.toString())));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not save the answer file", e);
            reportStatus.textProperty().bind(model.i18n().text("answers.failed", e.getMessage()));
        }
    }
}
