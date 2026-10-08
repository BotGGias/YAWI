package de.yawi.installer.ui;

import de.yawi.installer.core.error.ErrorCode;
import de.yawi.installer.core.error.InstallerException;
import de.yawi.installer.core.error.LogSetup;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.ManifestException;
import de.yawi.installer.core.manifest.ManifestProblem;
import de.yawi.installer.core.platform.PlatformFactory;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E17-S02-T01: the dialog explains, hints, names the log, hides the technical part - and never shows a stack trace. */
class ErrorDialogTest {

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private static ErrorDialog.Environment env(Locale locale) {
        return new ErrorDialog.Environment(Messages.load(), locale, PlatformFactory.current(), Optional.empty(),
                failure -> "report");
    }

    private static TextArea detailOf(Alert alert) {
        return (TextArea) alert.getDialogPane().getExpandableContent().lookup("#errorDetail");
    }

    @Test
    void manifestFailureShowsTextHintLogAndCollapsedProblems() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            ManifestException failure = new ManifestException("Manifest rejected",
                    List.of(ManifestProblem.error(3, 1, "unexpected element")));
            Alert alert = ErrorDialog.build(null, env(Locale.GERMAN), failure);

            assertEquals("Fehler", alert.getTitle());
            assertEquals("Die Konfiguration des Installers (Manifest) ist ungültig.", alert.getHeaderText());
            assertTrue(alert.getContentText().startsWith("Das ist ein Problem der Paketierung"), alert.getContentText());
            assertTrue(alert.getContentText().contains(LogSetup.currentLogFile().toString()), alert.getContentText());
            assertFalse(alert.getDialogPane().isExpanded(), "details start collapsed");
            assertTrue(detailOf(alert).getText().contains("line 3:1 - unexpected element"));
            assertEquals(List.of("Log öffnen", "Bericht speichern…", "Schließen"),
                    alert.getButtonTypes().stream().map(ButtonType::getText).toList());
        });
    }

    @Test
    void unexpectedFailureShowsTheCauseChainButNoStackTrace() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            RuntimeException cause = new IllegalStateException("inner", new java.io.IOException("disk"));
            InstallerException failure = InstallerException.wrap(cause);
            Alert alert = ErrorDialog.build(null, env(Locale.ENGLISH), failure);

            assertEquals("An unexpected error occurred: inner", alert.getHeaderText());
            String detail = detailOf(alert).getText();
            assertTrue(detail.contains("java.lang.IllegalStateException: inner"), detail);
            assertTrue(detail.contains("Caused by: java.io.IOException: disk"), detail);
            assertFalse(detail.contains("\tat "), "no stack trace: " + detail);
            assertFalse(detail.contains("ErrorDialogTest"), detail);
        });
    }

    @Test
    void knownFailureWithoutDetailHasNoExpandableContent() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            InstallerException failure = new InstallerException(ErrorCode.NOT_ENOUGH_SPACE, "full", "1 GiB", "0 B", "/x");
            Alert alert = ErrorDialog.build(null, env(Locale.ENGLISH), failure);
            assertNull(alert.getDialogPane().getExpandableContent());
            assertNotNull(alert.getDialogPane().lookupButton(alert.getButtonTypes().get(0)));
        });
    }
}
