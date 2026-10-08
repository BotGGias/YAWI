package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.ComponentInput.Option;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.Labeled;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The components page through the real wizard (E05). Needs a JavaFX toolkit
 * like {@link WizardNavigationTest}, whose helpers it reuses.
 */
class ComponentsPageTest {

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    /** Opens the wizard on the components page, laid out so that cells exist. */
    private static Wizard onComponentsPage(String manifest, Locale locale) {
        Wizard w = WizardNavigationTest.wizard(TestManifests.parse(manifest), locale);
        w.advanceTo("components");
        w.stage().show();
        layout(w);
        return w;
    }

    private static void layout(Wizard w) {
        w.root().applyCss();
        w.root().layout();
    }

    @SuppressWarnings("unchecked")
    private static CheckBoxTreeItem<Component> item(Wizard w, String id) {
        TreeView<Component> tree = (TreeView<Component>) w.root().lookup("#tree");
        for (TreeItem<Component> item : tree.getRoot().getChildren()) {
            if (item.getValue().id().equals(id)) {
                return (CheckBoxTreeItem<Component>) item;
            }
        }
        throw new AssertionError("no tree item for " + id);
    }

    @SuppressWarnings("unchecked")
    private static void highlight(Wizard w, String id) {
        ((TreeView<Component>) w.root().lookup("#tree")).getSelectionModel().select(item(w, id));
    }

    /** The check box of the row showing {@code id}, found by the cell text. */
    private static CheckBox checkBoxOf(Wizard w, String rowTextPrefix) {
        layout(w);
        for (Node node : w.root().lookup("#tree").lookupAll(".tree-cell")) {
            if (node instanceof Labeled cell && cell.getText() != null
                    && cell.getText().startsWith(rowTextPrefix) && cell.getGraphic() instanceof CheckBox box) {
                return box;
            }
        }
        throw new AssertionError("no row starting with '" + rowTextPrefix + "' in "
                + WizardNavigationTest.visibleTexts(w.root()));
    }

    private static String text(Wizard w, String fxId) {
        return ((Labeled) w.root().lookup("#" + fxId)).getText();
    }

    @Test
    void startsWithTheTypicalPresetAndLocksRequiredComponents() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/installer.xml", Locale.ENGLISH);
            assertEquals(Set.of("core"), w.model().getSelectedComponents());
            assertTrue(item(w, "core").isSelected());
            assertFalse(item(w, "server").isSelected());

            CheckBox core = checkBoxOf(w, "Game files (required)");
            assertTrue(core.isDisabled(), "required component is locked");
            assertFalse(checkBoxOf(w, "Dedicated server").isDisabled());
            assertTrue(w.flow().canGoNextProperty().get());

            highlight(w, "core");
            assertEquals("Base data without which nothing runs.", text(w, "description"));
            assertEquals("This component is required and cannot be deselected.", text(w, "lockHint"));
            w.stage().hide();
        });
    }

    @Test
    void tickingPullsInDependenciesAndLocksThem() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/manifest/chain.xml", Locale.ENGLISH);
            assertEquals(Set.of("c"), w.model().getSelectedComponents());

            item(w, "a").setSelected(true);
            assertEquals(Set.of("c", "b", "a"), w.model().getSelectedComponents());
            assertTrue(item(w, "b").isSelected());
            assertTrue(checkBoxOf(w, "B (needed by A)").isDisabled());
            assertTrue(checkBoxOf(w, "C (needed by B, A)").isDisabled());
            assertFalse(checkBoxOf(w, "A").isDisabled());

            highlight(w, "b");
            assertEquals("Needs C.", text(w, "description"));
            assertEquals("This component is needed by: A", text(w, "lockHint"));

            // Unticking a locked component is reverted.
            item(w, "b").setSelected(false);
            assertTrue(item(w, "b").isSelected());
            assertEquals(Set.of("c", "b", "a"), w.model().getSelectedComponents());

            // Unticking a frees b; c stays locked by b.
            item(w, "a").setSelected(false);
            assertEquals(Set.of("c", "b"), w.model().getSelectedComponents());
            assertFalse(checkBoxOf(w, "B").isDisabled());
            assertTrue(checkBoxOf(w, "C (needed by B)").isDisabled());
            w.stage().hide();
        });
    }

    @Test
    void nextIsBlockedWithoutAnySelection() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/manifest/chain.xml", Locale.ENGLISH);
            assertTrue(w.flow().canGoNextProperty().get());

            item(w, "c").setSelected(false);

            assertEquals(Set.of(), w.model().getSelectedComponents());
            assertFalse(w.flow().canGoNextProperty().get());
            assertTrue(w.button("nextButton").isDisabled());

            item(w, "b").setSelected(true);
            assertEquals(Set.of("c", "b"), w.model().getSelectedComponents());
            assertTrue(w.flow().canGoNextProperty().get());
            w.stage().hide();
        });
    }

    @Test
    void textsFollowTheLanguage() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/installer.xml", Locale.ENGLISH);
            highlight(w, "core");

            w.model().setLocale(Locale.GERMAN);
            layout(w);

            assertEquals("Basisdaten, ohne die nichts laeuft.", text(w, "description"));
            assertEquals("Diese Komponente ist erforderlich und kann nicht abgewählt werden.", text(w, "lockHint"));
            checkBoxOf(w, "Spieldateien (erforderlich)");
            assertEquals("Beschreibung", text(w, "descriptionTitle"));
            w.stage().hide();
        });
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> presets(Wizard w) {
        return (ComboBox<String>) w.root().lookup("#presets");
    }

    @Test
    void presetsSetTheSelectionAndFollowManualChanges() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/installer.xml", Locale.ENGLISH);
            ComboBox<String> presets = presets(w);
            assertTrue(w.root().lookup("#presetRow").isVisible());
            assertEquals("typical", presets.getValue());
            assertEquals(List.of("typical", "full", ""), presets.getItems());
            assertEquals("Typical", presets.getConverter().toString("typical"));
            assertEquals("Custom", presets.getConverter().toString(""));

            presets.setValue("full");
            assertEquals(Set.of("core", "server"), w.model().getSelectedComponents());
            assertTrue(item(w, "server").isSelected());

            // A manual change switches to "Custom" once the selection matches no preset...
            item(w, "server").setSelected(false);
            assertEquals("typical", presets.getValue(), "core alone is exactly 'typical'");

            Wizard chain = onComponentsPage("/manifest/chain.xml", Locale.ENGLISH);
            item(chain, "b").setSelected(true);
            assertEquals("", presets(chain).getValue(), "{c, b} matches no preset");
            presets(chain).setValue("full");
            assertEquals(Set.of("c", "b", "a"), chain.model().getSelectedComponents());

            // ... and the names follow the language.
            w.model().setLocale(Locale.GERMAN);
            assertEquals("Vollständig", presets.getConverter().toString("full"));
            assertEquals("Installationsart:", text(w, "presetLabel"));
            w.stage().hide();
            chain.stage().hide();
        });
    }

    @Test
    void presetRowDisappearsWithoutPresets() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/manifest/no-presets.xml", Locale.ENGLISH);
            assertFalse(w.root().lookup("#presetRow").isVisible());
            assertFalse(w.root().lookup("#presetRow").isManaged());
            assertEquals(Set.of("core"), w.model().getSelectedComponents());
            w.stage().hide();
        });
    }

    @Test
    void sizesFollowTheSelection() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/installer.xml", Locale.ENGLISH);
            assertEquals("Required space: 700 MiB", text(w, "installSize"));
            assertFalse(w.root().lookup("#downloadSize").isVisible(), "download equals install size");

            item(w, "server").setSelected(true);
            assertEquals("Required space: 750 MiB", text(w, "installSize"));
            assertEquals("Download size: 700 MiB", text(w, "downloadSize"));
            assertTrue(w.root().lookup("#downloadSize").isVisible());
            assertFalse(w.root().lookup("#spaceWarning").isVisible(), "temp/home has more than 750 MiB free");

            w.model().setLocale(Locale.GERMAN);
            assertEquals("Benötigter Platz: 750 MiB", text(w, "installSize"));
            w.stage().hide();
        });
    }

    @Test
    void inputsAppearForSelectedComponentsWithDefaults() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/manifest/full.xml", Locale.ENGLISH);
            assertFalse(w.root().lookup("#inputs").isVisible(), "no inputs while the server is not selected");
            assertFalse(w.root().lookup("#optionsTitle").isVisible());
            assertTrue(w.root().lookup("#input-serverPort") == null);

            item(w, "server").setSelected(true);
            layout(w);
            assertTrue(w.root().lookup("#inputs").isVisible());
            assertEquals("Options", text(w, "optionsTitle"));
            assertEquals("50000", ((TextField) w.root().lookup("#input-serverPort")).getText());
            assertTrue(((CheckBox) w.root().lookup("#input-autostart")).isSelected());
            assertEquals("lan", ((Option) ((ComboBox<?>) w.root().lookup("#input-mode")).getValue()).value());
            // Defaults are in the model right away.
            assertEquals("50000", w.model().getInputs().get("serverPort"));
            assertEquals("true", w.model().getInputs().get("autostart"));
            assertEquals("lan", w.model().getInputs().get("mode"));
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.contains("Port") && texts.contains("Start the server at login")
                    && texts.contains("Mode"), texts.toString());

            item(w, "server").setSelected(false);
            layout(w);
            assertFalse(w.root().lookup("#inputs").isVisible());
            assertTrue(w.root().lookup("#input-serverPort") == null);
            assertTrue(w.flow().canGoNextProperty().get());
            w.stage().hide();
        });
    }

    @Test
    void invalidInputsAreMarkedAndBlockNext() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/manifest/full.xml", Locale.ENGLISH);
            item(w, "server").setSelected(true);
            layout(w);
            TextField port = (TextField) w.root().lookup("#input-serverPort");
            assertTrue(w.flow().canGoNextProperty().get());

            port.setText("99");
            assertTrue(port.getStyleClass().contains("invalid"));
            assertFalse(w.flow().canGoNextProperty().get());
            assertTrue(w.button("nextButton").isDisabled());
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.contains("The value must be at least 1024."), texts.toString());
            assertEquals("99", w.model().getInputs().get("serverPort"));

            port.setText("abc");
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("Please enter a whole number."));

            w.model().setLocale(Locale.GERMAN);
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("Bitte eine ganze Zahl eingeben."));
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("Optionen"));

            port.setText("60000");
            assertFalse(port.getStyleClass().contains("invalid"));
            assertTrue(w.flow().canGoNextProperty().get());
            assertEquals("60000", w.model().getInputs().get("serverPort"));

            // Choice and bool write their values too.
            ((CheckBox) w.root().lookup("#input-autostart")).setSelected(false);
            assertEquals("false", w.model().getInputs().get("autostart"));
            @SuppressWarnings("unchecked")
            ComboBox<Option> mode = (ComboBox<Option>) w.root().lookup("#input-mode");
            mode.setValue(mode.getItems().get(1));
            assertEquals("internet", w.model().getInputs().get("mode"));
            assertEquals("Internet", mode.getConverter().toString(mode.getValue()));
            w.stage().hide();
        });
    }

    @Test
    void selectionSurvivesGoingBackAndReachesTheSummary() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = onComponentsPage("/installer.xml", Locale.ENGLISH);
            item(w, "server").setSelected(true);

            w.flow().back();
            w.flow().next();
            assertEquals(Set.of("core", "server"), w.model().getSelectedComponents());
            assertTrue(item(w, "server").isSelected());

            w.advanceTo("summary");
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.contains("Game files, Dedicated server"), texts.toString());
            assertTrue(texts.contains("750 MiB"), texts.toString());
            w.stage().hide();
        });
    }
}
