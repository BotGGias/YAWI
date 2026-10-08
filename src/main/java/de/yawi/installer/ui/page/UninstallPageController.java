package de.yawi.installer.ui.page;

import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.state.ExistingInstallation;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Uninstall page: the wizard's own page after the maintenance
 * page, shown only when the user chose to uninstall ({@link #isSkippable}
 * otherwise). It takes the {@link Uninstaller.Inventory} of the chosen
 * installation on every entry - files may have changed since the last look -
 * and asks the two questions the silent mode answers with {@code --purge}:
 * delete files changed since the installation, delete files the installer
 * did not put there. Both default to no. "Next" here is "Uninstall"; the
 * progress page runs it.
 */
public class UninstallPageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(UninstallPageController.class);

    @FXML
    private Label heading;
    @FXML
    private Label count;
    @FXML
    private Label noReverse;
    @FXML
    private VBox modifiedBox;
    @FXML
    private CheckBox deleteModified;
    @FXML
    private TitledPane modifiedPane;
    @FXML
    private ListView<String> modifiedList;
    @FXML
    private VBox userDataBox;
    @FXML
    private CheckBox deleteUserData;
    @FXML
    private TitledPane userDataPane;
    @FXML
    private ListView<String> userDataList;
    @FXML
    private Label hint;

    public UninstallPageController(InstallerModel model) {
        super(model);
    }

    @FXML
    private void initialize() {
        deleteModified.selectedProperty().bindBidirectional(model.deleteModifiedProperty());
        deleteUserData.selectedProperty().bindBidirectional(model.deleteUnrecordedProperty());
        for (var node : new javafx.scene.Node[] {modifiedBox, userDataBox, noReverse}) {
            node.managedProperty().bind(node.visibleProperty());
            node.setVisible(false);
        }
        heading.setText("");
        count.setText("");
    }

    /** Only on the way to an uninstall; the page name is reserved, so nothing else ever shows it. */
    @Override
    public boolean isSkippable() {
        return !uninstalling();
    }

    @Override
    public NextAction nextAction() {
        return NextAction.UNINSTALL;
    }

    /** A fresh look every time: the user may have gone back and forth, files may have changed meanwhile. */
    @Override
    public void onEnter() {
        Optional<InstallationRecord> record = model.getExistingInstallation().flatMap(ExistingInstallation::record);
        if (record.isEmpty()) {
            // Cannot happen through the maintenance page (it only lets intact installations through).
            LOG.warn("Uninstall page entered without an installation record");
            return;
        }
        Uninstaller.Inventory inventory = new Uninstaller(model.getManifest(), model.getPlatform(), record.get(),
                model.getRegistry()).inspect();
        String product = model.getManifest().product().name();
        String destination = record.get().destination().toString();
        heading.textProperty().bind(model.i18n().text("uninstall.heading", product, record.get().productVersion(),
                destination));
        count.textProperty().bind(model.i18n().text("uninstall.count", inventory.files().size()));

        modifiedList.getItems().setAll(inventory.modified());
        modifiedBox.setVisible(!inventory.modified().isEmpty());
        deleteModified.textProperty().bind(model.i18n().text("uninstall.modified", inventory.modified().size()));

        userDataList.getItems().setAll(inventory.unrecorded());
        userDataBox.setVisible(!inventory.unrecorded().isEmpty());
        deleteUserData.textProperty().bind(model.i18n().text("uninstall.userData", inventory.unrecorded().size()));

        noReverse.setVisible(!inventory.stepsWithoutReverse().isEmpty());
        noReverse.textProperty().bind(Bindings.createStringBinding(
                () -> model.i18n().get("uninstall.noReverse", String.join(", ", inventory.stepsWithoutReverse())),
                model.localeProperty()));
        LOG.info("Uninstall page for {} under {}: {} file(s), {} changed, {} not recorded", product, destination,
                inventory.files().size(), inventory.modified().size(), inventory.unrecorded().size());
    }
}
