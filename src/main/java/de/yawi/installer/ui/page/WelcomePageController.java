package de.yawi.installer.ui.page;

import de.yawi.installer.ui.InstallerModel;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Welcome page: product name and version, language selection. */
public class WelcomePageController extends WizardPageController {

    private static final Logger LOG = LoggerFactory.getLogger(WelcomePageController.class);

    @FXML
    private Label title;
    @FXML
    private Label version;
    @FXML
    private ComboBox<Locale> language;

    public WelcomePageController(InstallerModel model) {
        super(model);
    }

    /** Nothing to choose while uninstalling. */
    @Override
    public boolean isSkippable() {
        return uninstalling();
    }

    @FXML
    private void initialize() {
        // Product name and version come from the manifest, the sentence
        // around them from the bundle; bound so a language switch updates it.
        var product = model.getManifest().product();
        title.textProperty().bind(model.i18n().text("welcome.title", product.name()));
        version.textProperty().bind(model.i18n().text("welcome.version", product.version()));

        language.setConverter(new StringConverter<>() {
            @Override
            public String toString(Locale locale) {
                // Each language is shown in its own language.
                return locale == null ? "" : locale.getDisplayLanguage(locale);
            }

            @Override
            public Locale fromString(String text) {
                throw new UnsupportedOperationException("read-only combo box");
            }
        });
        language.getItems().setAll(model.getAvailableLanguages());
        language.setValue(model.getLocale());
    }

    /** Every bound text on screen follows the model's locale property. */
    @FXML
    private void languageChanged() {
        Locale selected = language.getValue();
        if (selected != null && !selected.equals(model.getLocale())) {
            model.setLocale(selected);
            LOG.info("Setup language set to {}", selected.toLanguageTag());
        }
    }
}
