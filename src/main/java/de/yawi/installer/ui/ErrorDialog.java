package de.yawi.installer.ui;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.platform.Platform;
import javafx.application.HostServices;
import javafx.event.ActionEvent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one error dialog of the installer: what happened, why,
 * what to do, where the log is - and the technical detail folded away.
 * Never a stack trace. "Open log" and "Save report" keep the dialog open.
 *
 * <p>{@link #build} returns the {@link Alert} unshown so tests can inspect
 * it; {@link #show} blocks until the user closes it.
 */
public final class ErrorDialog {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorDialog.class);

    /** Everything the dialog needs that is not the failure itself. */
    public record Environment(Messages messages, Locale locale, Platform platform,
                              Optional<HostServices> hostServices, Function<InstallerException, String> report) {
    }

    private ErrorDialog() {
    }

    public static void show(Window owner, Environment env, InstallerException failure) {
        Alert alert = build(owner, env, failure);
        alert.showAndWait();
    }

    public static Alert build(Window owner, Environment env, InstallerException failure) {
        Messages m = env.messages();
        Locale locale = env.locale();
        ButtonType openLog = new ButtonType(m.get(locale, "error.dialog.openLog"), ButtonBar.ButtonData.HELP);
        ButtonType saveReport = new ButtonType(m.get(locale, "error.dialog.saveReport"), ButtonBar.ButtonData.OTHER);
        ButtonType close = new ButtonType(m.get(locale, "wizard.close"), ButtonBar.ButtonData.CANCEL_CLOSE);

        Alert alert = new Alert(Alert.AlertType.ERROR, "", openLog, saveReport, close);
        alert.setTitle(m.get(locale, "error.dialog.title"));
        alert.setHeaderText(failure.userMessage(m, locale));
        alert.setContentText(failure.hint(m, locale) + "\n\n"
                + m.get(locale, "error.dialog.logHint", LogSetup.currentLogFile().toString()));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.setResizable(true);

        String detail = detailText(failure);
        if (detail != null) {
            TextArea area = new TextArea(detail);
            area.setId("errorDetail");
            area.setEditable(false);
            area.setWrapText(false);
            area.setPrefRowCount(10);
            Label caption = new Label(m.get(locale, "error.dialog.details"));
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(4, caption, area);
            alert.getDialogPane().setExpandableContent(box);
            alert.getDialogPane().setExpanded(false);
        }

        // Help and Other buttons act without closing the dialog.
        keepOpen(alert, openLog, () -> Diagnostics.openLog(env.hostServices()));
        keepOpen(alert, saveReport, () -> {
            try {
                Diagnostics.saveReport(alert.getDialogPane().getScene().getWindow(), env.platform(),
                                m.get(locale, "report.chooserTitle"), env.report().apply(failure))
                        .ifPresent(file -> alert.setContentText(alert.getContentText() + "\n\n"
                                + m.get(locale, "report.saved", file.toString())));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not save the diagnostic report", e);
                alert.setContentText(alert.getContentText() + "\n\n" + m.get(locale, "report.failed", e.getMessage()));
            }
        });
        return alert;
    }

    private static void keepOpen(Alert alert, ButtonType type, Runnable action) {
        Button button = (Button) alert.getDialogPane().lookupButton(type);
        button.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            action.run();
        });
    }

    /**
     * The technical detail: the failure's own detail if it has one, else -
     * for the unexpected - the chain of causes as class and message. Stack
     * traces belong in the log only.
     */
    static String detailText(InstallerException failure) {
        if (failure.detail().isPresent()) {
            return failure.detail().get();
        }
        if (failure.code() != ErrorCode.GENERAL) {
            return null;
        }
        StringBuilder sb = new StringBuilder(failure.getMessage());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            sb.append("\nCaused by: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        return sb.toString();
    }
}
