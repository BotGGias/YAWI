package de.yawi.installer.ui;

import de.yawi.installer.ui.i18n.LocaleManager;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/**
 * "Really cancel?" in the chosen language. While the installation runs the
 * question mentions the rollback that follows.
 */
public final class CancelDialog {

    private CancelDialog() {
    }

    /** Blocks until the user answers; true means "yes, cancel". */
    public static boolean confirm(Window owner, LocaleManager i18n, boolean installing) {
        return confirm(owner, i18n, installing ? "cancel.question.installing" : "cancel.question");
    }

    /** @param questionKey the bundle key of the question; an uninstall in progress warns differently */
    public static boolean confirm(Window owner, LocaleManager i18n, String questionKey) {
        ButtonType yes = new ButtonType(i18n.get("wizard.yes"), ButtonBar.ButtonData.YES);
        ButtonType no = new ButtonType(i18n.get("wizard.no"), ButtonBar.ButtonData.NO);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, i18n.get(questionKey), yes, no);
        alert.initOwner(owner);
        alert.setTitle(i18n.get("cancel.title"));
        alert.setHeaderText(null);
        // "No" is the safe default: Enter (and Escape) keep the wizard running.
        ((Button) alert.getDialogPane().lookupButton(yes)).setDefaultButton(false);
        Button noButton = (Button) alert.getDialogPane().lookupButton(no);
        noButton.setDefaultButton(true);
        noButton.setCancelButton(true);
        return alert.showAndWait().filter(yes::equals).isPresent();
    }
}
