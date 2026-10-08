package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.DestinationValidator.Check;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.DestinationPageController;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The destination page through the real wizard (E06). Needs a JavaFX toolkit
 * like {@link WizardNavigationTest}, whose helpers it reuses. The file system
 * check is asynchronous; {@link #check(Wizard)} runs it and waits.
 */
class DestinationPageTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private static Wizard onDestinationPage(String manifest) {
        Wizard w = WizardNavigationTest.wizardWithoutDestination(TestManifests.parse(manifest), Locale.ENGLISH);
        w.advanceTo("destination");
        return w;
    }

    private static TextField pathField(Wizard w) {
        return (TextField) w.root().lookup("#path");
    }

    /** Sets the path and waits for the check of it. */
    private static Check enter(Wizard w, String text) {
        pathField(w).setText(text);
        return check(w);
    }

    private static Check check(Wizard w) {
        return WizardNavigationTest.await(((DestinationPageController) w.flow().getCurrentPage()).checkNow());
    }

    private static List<String> messages(Wizard w) {
        return ((VBox) w.root().lookup("#messages")).getChildren().stream()
                .map(n -> ((Labeled) n).getText()).toList();
    }

    private static boolean nextDisabled(Wizard w) {
        return w.button("nextButton").isDisabled();
    }

    // --- E06-S01 ----------------------------------------------------------

    @Test
    void proposesThePlatformDefaultAndWritesItToTheModel() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizardWithoutDestination(TestManifests.bundled(), Locale.ENGLISH);
            assertNull(w.model().getDestination(), "nothing chosen before the page is visited");

            w.advanceTo("destination");
            check(w);

            String shown = pathField(w).getText();
            assertTrue(shown.contains("your-product"), shown);
            assertFalse(shown.contains("$HOME"), "variables expanded: " + shown);
            assertEquals(Path.of(shown), w.model().getDestination());
            assertEquals(w.model().getDestination(), w.model().getEffectiveDestination());
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("Setup will install YOUR INSTALLER")), texts.toString());
            assertFalse(nextDisabled(w));
        });
    }

    @Test
    void handEditedPathReachesModelAndSummary() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            Path target = tmp.resolve("my folder");

            enter(w, target.toString());
            assertEquals(target, w.model().getDestination());
            assertFalse(nextDisabled(w));

            w.advanceTo("summary");
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains(target.toString()));

            // Going back keeps the text.
            while (!w.flow().getCurrentPage().name().equals("destination")) {
                w.flow().back();
            }
            assertEquals(target.toString(), pathField(w).getText());
        });
    }

    @Test
    void variablesAndTildeAreExpanded() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            pathField(w).setText("~/su-expanded");
            Path expected = w.model().getPlatform().homeDir().resolve("su-expanded");
            assertEquals(expected, w.model().getDestination());
        });
    }

    @Test
    void allowUserChangeFalseLocksFieldAndBrowse() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/manifest/fixed-destination.xml");
            assertFalse(pathField(w).isEditable());
            assertTrue(((Button) w.root().lookup("#browse")).isDisabled());
            assertTrue(pathField(w).getText().endsWith("fd-fixed"), pathField(w).getText());
            check(w);
            assertFalse(nextDisabled(w));

            Wizard free = onDestinationPage("/installer.xml");
            assertTrue(pathField(free).isEditable());
            assertFalse(((Button) free.root().lookup("#browse")).isDisabled());
        });
    }

    // --- E06-S02 ----------------------------------------------------------

    @Test
    void nextWaitsForTheCheck() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            pathField(w).setText(tmp.toString());
            assertTrue(nextDisabled(w), "locked while the check is pending");
            check(w);
            assertFalse(nextDisabled(w));
        });
    }

    @Test
    void syntaxProblemsAreShownAtTheFieldAndBlockNext() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            Path before = w.model().getDestination();

            enter(w, "relative/folder");
            assertTrue(nextDisabled(w), "relative path");
            assertEquals(List.of("'relative/folder' is not an absolute path. Please enter the full path."), messages(w));
            assertTrue(pathField(w).getStyleClass().contains("invalid"));
            assertEquals(before, w.model().getDestination(), "model keeps the last valid path");

            enter(w, "   ");
            assertTrue(nextDisabled(w), "empty");
            assertEquals(List.of("Please enter a destination folder."), messages(w));

            enter(w, tmp.toString());
            assertFalse(nextDisabled(w));
            assertEquals(List.of(), messages(w));
            assertFalse(pathField(w).getStyleClass().contains("invalid"));
        });
    }

    @Test
    void fileAsDestinationIsAnError() throws Exception {
        Path file = Files.writeString(tmp.resolve("file.txt"), "x");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            enter(w, file.toString());
            assertTrue(nextDisabled(w));
            assertEquals(List.of(file + " is a file, not a folder."), messages(w));
        });
    }

    @Test
    void nonEmptyFolderNeedsConfirmation() throws Exception {
        Path full = Files.createDirectory(tmp.resolve("full"));
        Files.writeString(full.resolve("old.txt"), "x");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            CheckBox confirm = (CheckBox) w.root().lookup("#confirmNotEmpty");
            assertFalse(confirm.isVisible(), "hidden for the default folder");

            enter(w, full.toString());
            assertTrue(confirm.isVisible());
            assertTrue(nextDisabled(w), "warning shown, confirmation missing");
            assertEquals(List.of("The folder " + full + " already exists and is not empty. Existing files may be overwritten."),
                    messages(w));
            Node warning = ((VBox) w.root().lookup("#messages")).getChildren().get(0);
            assertTrue(warning.getStyleClass().contains("warning"), "a warning, not an error");

            confirm.setSelected(true);
            assertFalse(nextDisabled(w));

            // Another folder needs its own confirmation.
            enter(w, tmp.resolve("fresh").toString());
            assertFalse(confirm.isSelected());
            assertFalse(confirm.isVisible());
            assertFalse(nextDisabled(w));
        });
    }

    @Test
    void spaceLineAndMessagesFollowTheLanguage() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            enter(w, tmp.toString());
            Labeled space = (Labeled) w.root().lookup("#space");
            assertTrue(space.isVisible());
            assertTrue(space.getText().startsWith("Free space: "), space.getText());
            assertTrue(space.getText().contains("required: 700 MiB"), space.getText());

            enter(w, "relative");
            w.model().setLocale(Locale.GERMAN);
            assertEquals(List.of("„relative“ ist kein absoluter Pfad. Bitte geben Sie den vollständigen Pfad an."), messages(w));
            assertEquals("Zielordner", w.pageTitle());
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.contains("Durchsuchen…"), texts.toString());
            assertTrue(texts.stream().anyMatch(t -> t.startsWith("Das Setup installiert YOUR INSTALLER")), texts.toString());
        });
    }

    @Test
    void reenteringChecksAgainWithTheCurrentSelection() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            enter(w, tmp.toString());
            String before = ((Labeled) w.root().lookup("#space")).getText();

            w.flow().back();
            w.model().getSelectedComponents().add("server");
            w.advanceTo("destination");
            check(w);

            String after = ((Labeled) w.root().lookup("#space")).getText();
            assertTrue(after.contains("750 MiB"), after);
            assertFalse(after.equals(before));
        });
    }

    // --- E06-S03 ----------------------------------------------------------

    private static RadioButton scopeAll(Wizard w) {
        return (RadioButton) w.root().lookup("#scopeAll");
    }

    @Test
    void scopeSwitchMovesBetweenTheTwoDefaults() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            RadioButton user = (RadioButton) w.root().lookup("#scopeUser");
            assertTrue(user.isSelected(), "just for me is the default");
            assertFalse(w.model().isSystemWide());
            Path userDefault = w.model().defaultDestination(false);
            Path allDefault = w.model().defaultDestination(true);
            assertEquals(userDefault.toString(), pathField(w).getText());

            scopeAll(w).setSelected(true);
            assertTrue(w.model().isSystemWide());
            assertEquals(allDefault.toString(), pathField(w).getText());
            assertEquals(allDefault, w.model().getDestination());
            assertTrue(allDefault.toString().contains("your-product") || allDefault.toString().contains("your-installer"),
                    allDefault.toString());

            user.setSelected(true);
            assertFalse(w.model().isSystemWide());
            assertEquals(userDefault.toString(), pathField(w).getText());
        });
    }

    @Test
    void scopeSwitchStaysUsableWhenTheUserMayNotEditThePath() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/manifest/fixed-destination.xml");
            assertFalse(scopeAll(w).isDisabled());
            scopeAll(w).setSelected(true);
            assertEquals(w.model().defaultDestination(true).toString(), pathField(w).getText());
            assertFalse(pathField(w).isEditable());
        });
    }

    @Test
    void systemPathsShowTheRightsHintAndSetElevationRequired() throws Exception {
        assumeTrue(TestManifests.bundled().destination().systemWideFor(
                de.yawi.installer.core.platform.PlatformFactory.current().os()).isPresent());
        assumeFalse(de.yawi.installer.core.platform.PlatformFactory.current().isElevated(), "root writes anywhere");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onDestinationPage("/installer.xml");
            Labeled hint = (Labeled) w.root().lookup("#elevationHint");
            enter(w, tmp.toString());
            assertFalse(hint.isVisible());
            assertFalse(w.model().isElevationRequired());

            // Via the switch ...
            scopeAll(w).setSelected(true);
            check(w);
            assertTrue(hint.isVisible(), "system default needs rights");
            assertTrue(w.model().isElevationRequired());
            assertFalse(nextDisabled(w), "not an error: E12 asks for the rights");
            assertEquals(List.of(), messages(w));
            assertTrue(scopeAll(w).getText().contains("administrator rights"), scopeAll(w).getText());

            // ... and by hand, with the switch left alone.
            RadioButton user = (RadioButton) w.root().lookup("#scopeUser");
            user.setSelected(true);
            Path system = w.model().getPlatform().defaultSystemInstallDir("yawi-installer-test");
            enter(w, system.toString());
            assertTrue(hint.isVisible());
            assertTrue(w.model().isElevationRequired());
            assertTrue(user.isSelected());

            enter(w, tmp.toString());
            assertFalse(hint.isVisible());
            assertFalse(w.model().isElevationRequired());
        });
    }
}
