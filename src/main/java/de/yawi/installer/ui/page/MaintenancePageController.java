package de.yawi.installer.ui.page;

import de.yawi.installer.core.download.Version;
import de.yawi.installer.core.state.ExistingInstallation;
import de.yawi.installer.ui.InstallerModel;
import de.yawi.installer.ui.InstallerModel.InstallMode;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Maintenance page: sits in front of the first manifest page and
 * shows itself only when the register knows an installation of this
 * product. The user picks what to do with it - modify or update run the
 * normal wizard with the previous answers prefilled; uninstall
 * goes to the uninstall page and from there straight to the progress page;
 * repair reinstalls the recorded selection. An orphaned entry (folder
 * or record gone) can be removed here; once nothing is left the page lets
 * the user through to a fresh installation.
 */
public class MaintenancePageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(MaintenancePageController.class);

    @FXML
    private Label heading;
    @FXML
    private HBox selectBox;
    @FXML
    private ComboBox<ExistingInstallation> installations;
    @FXML
    private Label found;
    @FXML
    private Label versionInfo;
    @FXML
    private VBox modes;
    @FXML
    private ToggleGroup mode;
    @FXML
    private RadioButton modeUpdate;
    @FXML
    private RadioButton modeModify;
    @FXML
    private RadioButton modeRepair;
    @FXML
    private RadioButton modeUninstall;
    @FXML
    private VBox orphanBox;
    @FXML
    private Label orphan;
    @FXML
    private Button removeEntry;
    @FXML
    private Label status;

    private final BooleanProperty nextAllowed = new SimpleBooleanProperty(this, "nextAllowed", false);
    /** What {@link #onLeave} last applied, so going back and forth does not overwrite the user's edits. */
    private ExistingInstallation appliedInstallation;
    private InstallMode appliedMode;

    public MaintenancePageController(InstallerModel model) {
        super(model);
    }

    @FXML
    private void initialize() {
        heading.textProperty().bind(model.i18n().text("maintenance.heading", model.getManifest().product().name()));

        installations.setItems(model.getExistingInstallations());
        installations.setConverter(new StringConverter<>() {
            @Override
            public String toString(ExistingInstallation e) {
                return e == null ? "" : e.destination() + " (" + e.installedVersion() + ")";
            }

            @Override
            public ExistingInstallation fromString(String s) {
                return null;
            }
        });
        installations.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ExistingInstallation item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : installations.getConverter().toString(item));
            }
        });
        // One installation needs no chooser.
        selectBox.visibleProperty().bind(Bindings.size(model.getExistingInstallations()).greaterThan(1));
        selectBox.managedProperty().bind(selectBox.visibleProperty());
        installations.valueProperty().addListener((obs, old, chosen) -> show(chosen));

        // Repair and modify are live since E14-S04.
        mode.selectedToggleProperty().addListener((obs, old, toggle) -> updateNextAllowed());

        for (Node node : new Node[] {found, versionInfo, modes, orphanBox, status}) {
            node.managedProperty().bind(node.visibleProperty());
        }
        model.getExistingInstallations().addListener((ListChangeListener<ExistingInstallation>) change -> {
            if (installations.getValue() == null && !model.getExistingInstallations().isEmpty()) {
                installations.setValue(model.getExistingInstallations().get(0));
            }
            updateNextAllowed();
        });
        if (!model.getExistingInstallations().isEmpty()) {
            installations.setValue(model.getExistingInstallations().get(0)); // newest first
        } else {
            show(null);
        }
    }

    /** Puts one installation on the page: what it is, how its version compares, what can be done. */
    private void show(ExistingInstallation chosen) {
        model.setExistingInstallation(chosen);
        found.textProperty().unbind();
        versionInfo.textProperty().unbind();
        orphan.textProperty().unbind();
        status.textProperty().unbind();
        status.setVisible(false);
        if (chosen == null) {
            found.setVisible(false);
            versionInfo.setVisible(false);
            modes.setVisible(false);
            orphanBox.setVisible(false);
            updateNextAllowed();
            return;
        }
        String name = model.getManifest().product().name();
        String installed = chosen.installedVersion();
        String offered = model.getManifest().product().version();
        found.textProperty().bind(model.i18n().text("maintenance.found", name, installed, chosen.destination().toString()));
        int cmp = Version.compare(offered, installed);
        String key = cmp > 0 ? "maintenance.version.newer" : cmp < 0 ? "maintenance.version.older" : "maintenance.version.same";
        versionInfo.textProperty().bind(model.i18n().text(key, installed, offered));

        boolean intact = chosen.isIntact();
        // "X is installed in Y" would contradict the orphan warning below it.
        found.setVisible(intact);
        versionInfo.setVisible(intact);
        modes.setVisible(intact);
        orphanBox.setVisible(!intact);
        if (intact) {
            modeUpdate.setDisable(cmp <= 0);
            mode.selectToggle(cmp > 0 ? modeUpdate : modeModify);
        } else {
            LOG.info("Registered installation under {} is orphaned: {}", chosen.destination(), chosen.detail());
            mode.selectToggle(null);
            orphan.textProperty().bind(model.i18n().text("maintenance.orphan", chosen.destination().toString()));
        }
        updateNextAllowed();
    }

    private void updateNextAllowed() {
        ExistingInstallation chosen = installations.getValue();
        boolean none = model.getExistingInstallations().isEmpty();
        boolean chosenIntact = chosen != null && chosen.isIntact() && mode.getSelectedToggle() != null;
        nextAllowed.set(none || chosenIntact);
    }

    private InstallMode selectedMode() {
        var toggle = mode.getSelectedToggle();
        if (toggle == modeUpdate) {
            return InstallMode.UPDATE;
        }
        if (toggle == modeRepair) {
            return InstallMode.REPAIR;
        }
        if (toggle == modeUninstall) {
            return InstallMode.UNINSTALL;
        }
        return InstallMode.MODIFY;
    }

    @Override
    public ObservableBooleanValue nextAllowed() {
        return nextAllowed;
    }

    /** Nothing registered, nothing to maintain. */
    @Override
    public boolean isSkippable() {
        return model.getExistingInstallations().isEmpty();
    }

    /** Applies the choice to the model - once per (installation, mode), so edits on later pages survive Back. */
    @Override
    public void onLeave() {
        ExistingInstallation chosen = installations.getValue();
        if (chosen == null || model.getExistingInstallations().isEmpty() || !chosen.isIntact()) {
            model.setInstallMode(InstallMode.FRESH);
            model.setExistingInstallation(null);
            return;
        }
        InstallMode selected = selectedMode();
        if (chosen.equals(appliedInstallation) && selected == appliedMode) {
            return;
        }
        appliedInstallation = chosen;
        appliedMode = selected;
        model.setInstallMode(selected);
        model.setExistingInstallation(chosen);
        chosen.record().ifPresent(model::prefillFrom);
        LOG.info("Maintenance: {} of the installation under {}", selected, chosen.destination());
    }

    /** Drops an orphaned entry from the register. */
    @FXML
    private void removeEntry() {
        ExistingInstallation chosen = installations.getValue();
        if (chosen == null) {
            return;
        }
        status.textProperty().unbind();
        try {
            if (model.getRegistry().isPresent()) {
                model.getRegistry().get().unregister(chosen.registration());
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not remove the register entry {}", chosen.registration().file(), e);
            status.textProperty().bind(model.i18n().text("maintenance.orphan.removeFailed", e.getMessage()));
            status.setVisible(true);
            return;
        }
        LOG.info("Removed the orphaned register entry for {}", chosen.destination());
        installations.setValue(null);
        model.getExistingInstallations().remove(chosen);
        if (model.getExistingInstallations().isEmpty()) {
            show(null);
            status.textProperty().bind(model.i18n().text("maintenance.orphan.removed"));
            status.setVisible(true);
        } else {
            installations.setValue(model.getExistingInstallations().get(0));
        }
    }
}
