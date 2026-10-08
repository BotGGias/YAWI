package de.yawi.installer.ui.page;

import de.yawi.installer.core.manifest.ResourceRef;
import de.yawi.installer.ui.InstallerModel;
import javafx.beans.value.ObservableBooleanValue;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * License page: the text from {@code wizard/license}, "Next" only after the
 * user accepted. Skipped entirely when the manifest names no license.
 */
public class LicensePageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(LicensePageController.class);

    @FXML
    private TextArea licenseText;
    @FXML
    private CheckBox accept;

    private boolean loaded;

    public LicensePageController(InstallerModel model) {
        super(model);
    }

    @Override
    public boolean isSkippable() {
        return license().isEmpty() || uninstalling();
    }

    @Override
    public ObservableBooleanValue nextAllowed() {
        return accept.selectedProperty();
    }

    /** Loaded on first entry, not at startup: a file reference may be slow or missing. */
    @Override
    public void onEnter() {
        if (loaded) {
            return;
        }
        loaded = true;
        String ref = license().orElseThrow();
        try {
            licenseText.setText(ResourceRef.readText(ref));
        } catch (IOException e) {
            // Without the text there is nothing to accept; Next stays locked
            // because the check box is disabled.
            LOG.error("License text {} could not be read: {}", ref, e.getMessage());
            licenseText.textProperty().bind(model.i18n().text("license.unreadable", ref));
            accept.setDisable(true);
        }
    }

    private Optional<String> license() {
        return model.getManifest().wizard().licenseOrEmpty();
    }
}
