package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordWriter;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.MaintenancePageController;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E14-S02: the maintenance page appears only for a registered installation,
 * prefills the wizard from its record and cleans up orphaned entries. Runs
 * where a display is available.
 */
class MaintenancePageTest {

    private static final String PRODUCT = "your-product";

    @TempDir
    Path tmp;

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private InstallationRegistry registry() {
        return InstallationRegistry.at(tmp.resolve("registry"), PRODUCT);
    }

    /** A wizard whose model knows the register - what WizardApp does at startup. */
    private Wizard wizard(InstallManifest manifest) {
        InstallerModel model = new InstallerModel(manifest, PlatformFactory.current());
        model.setLocale(Locale.ENGLISH);
        InstallationRegistry registry = registry();
        model.setRegistry(registry);
        model.getExistingInstallations().setAll(registry.installations());
        Stage stage = new Stage();
        WizardFlow flow = new WizardFlow(PageRegistry.build(model));
        WizardFrameController frame = WizardFrameController.show(stage, model, flow);
        return new Wizard(stage, model, frame, flow);
    }

    /** Installs "on disk" the cheap way: a record under the destination plus the register entry. */
    private InstallationRecord installed(Path destination, String version) throws IOException {
        InstallationRecord record = new InstallationRecord(PRODUCT, version, Instant.parse("2026-09-14T10:00:00Z"),
                destination, List.of("core", "server"), Map.of("serverPort", "51234"));
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(destination), record)) {
            record.stepStarted("unpack");
        }
        Files.writeString(destination.resolve("settings.ini"), "installed\n");
        registry().register(record);
        return record;
    }

    private static String text(Wizard w, String fxId) {
        return ((Labeled) w.root().lookup("#" + fxId)).getText();
    }

    private static Node node(Wizard w, String fxId) {
        return w.root().lookup("#" + fxId);
    }

    @Test
    void withoutARegisteredInstallationThePageIsThereButSkipped() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            assertEquals("maintenance", w.flow().getPages().get(0).name(), "in front of the manifest pages");
            assertEquals("welcome", w.flow().getCurrentPage().name());
            assertTrue(w.button("backButton").isDisabled(), "nothing to go back to");
            assertEquals(InstallerModel.InstallMode.FRESH, w.model().getInstallMode());
        });
    }

    @Test
    void registeredInstallationOpensTheMaintenancePageAndPrefillsTheWizard() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("YOUR INSTALLER"));
        installed(dest, "0.0.9"); // older than the bundled manifest's 0.1.0
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());

            assertEquals("maintenance", w.flow().getCurrentPage().name());
            assertEquals("Existing installation", w.pageTitle());
            assertTrue(text(w, "heading").startsWith("YOUR INSTALLER is already installed"), text(w, "heading"));
            assertEquals("YOUR INSTALLER 0.0.9 is installed in " + dest.toAbsolutePath().normalize() + ".",
                    text(w, "found"));
            assertEquals("This installer brings version 0.1.0; version 0.0.9 is installed.", text(w, "versionInfo"));
            assertFalse(node(w, "selectBox").isVisible(), "one installation needs no chooser");
            assertTrue(node(w, "modes").isVisible());
            assertFalse(node(w, "orphanBox").isVisible());
            assertTrue(((RadioButton) node(w, "modeUpdate")).isSelected(), "a newer version proposes an update");
            assertFalse(node(w, "modeRepair").isDisabled(), "repair is live since E14-S04");
            assertFalse(node(w, "modeUninstall").isDisabled(), "E14-S03");
            assertFalse(w.button("nextButton").isDisabled());

            w.advance();
            assertEquals("welcome", w.flow().getCurrentPage().name());
            assertEquals(InstallerModel.InstallMode.UPDATE, w.model().getInstallMode());
            assertEquals(dest.toAbsolutePath().normalize(), w.model().getDestination());
            assertEquals(Set.of("core", "server"), w.model().getSelectedComponents());
            assertEquals("51234", w.model().getInputs().get("serverPort"));
            assertFalse(w.button("backButton").isDisabled(), "the maintenance page is a real page now");

            w.advanceTo("components");
            assertEquals("51234", ((TextField) node(w, "input-serverPort")).getText(), "input prefilled");

            w.advanceTo("destination");
            assertEquals(dest.toAbsolutePath().normalize().toString(), ((TextField) node(w, "path")).getText());
            assertTrue(((RadioButton) node(w, "scopeUser")).isSelected());
            WizardNavigationTest.await(((de.yawi.installer.ui.page.DestinationPageController) w.flow().getCurrentPage()).checkNow());
            CheckBox confirm = (CheckBox) node(w, "confirmNotEmpty");
            assertTrue(confirm.isVisible(), "the folder has files in it");
            assertTrue(confirm.isSelected(), "…but it is the installation we are updating");
            assertFalse(w.button("nextButton").isDisabled());
        });
    }

    @Test
    void sameVersionProposesModifyAndGoingBackKeepsLaterEdits() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("same"));
        installed(dest, "0.1.0");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            assertEquals("This installer brings the same version (0.1.0).", text(w, "versionInfo"));
            assertTrue(((RadioButton) node(w, "modeModify")).isSelected());
            assertTrue(node(w, "modeUpdate").isDisabled(), "nothing newer to update to");

            w.advance();
            assertEquals(InstallerModel.InstallMode.MODIFY, w.model().getInstallMode());
            w.model().getSelectedComponents().remove("server"); // an edit on a later page
            w.flow().back();
            assertEquals("maintenance", w.flow().getCurrentPage().name());
            w.advance();
            assertEquals(Set.of("core"), w.model().getSelectedComponents(), "same choice again: no re-prefill");
        });
    }

    @Test
    void repairLocksTheComponentSelectionButKeepsInputsEditable() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("repair"));
        installed(dest, "0.1.0");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            ((RadioButton) node(w, "modeRepair")).setSelected(true);
            w.advance();
            assertEquals(InstallerModel.InstallMode.REPAIR, w.model().getInstallMode());

            w.advanceTo("components");
            assertTrue(node(w, "presets").isDisabled(), "the selection is locked in repair");
            assertFalse(((TextField) node(w, "input-serverPort")).isDisabled(), "inputs stay editable");
        });
    }

    @Test
    void orphanedEntryCanBeRemovedAndThenTheWizardInstallsFresh() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("gone"));
        installed(dest, "0.1.0");
        Files.walk(dest).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        assertFalse(Files.exists(dest));
        Path entry = registry().list().get(0).file();
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            assertEquals("maintenance", w.flow().getCurrentPage().name());
            assertFalse(node(w, "modes").isVisible(), "nothing can be done with a missing folder");
            assertFalse(node(w, "found").isVisible(), "'is installed in' would contradict the warning");
            assertTrue(node(w, "orphanBox").isVisible());
            assertTrue(text(w, "orphan").startsWith("The installation in " + dest.toAbsolutePath().normalize()
                    + " could not be found"), text(w, "orphan"));
            assertTrue(w.button("nextButton").isDisabled(), "remove the entry first");

            ((Button) node(w, "removeEntry")).fire();

            assertFalse(Files.exists(entry), "register entry deleted");
            assertTrue(w.model().getExistingInstallations().isEmpty());
            assertTrue(((MaintenancePageController) w.flow().getCurrentPage()).isSkippable());
            assertTrue(node(w, "status").isVisible());
            assertEquals("The entry has been removed. Click Next to install.", text(w, "status"));
            assertFalse(w.button("nextButton").isDisabled());

            w.advance();
            assertEquals("welcome", w.flow().getCurrentPage().name());
            assertEquals(InstallerModel.InstallMode.FRESH, w.model().getInstallMode());
            assertTrue(w.model().getExistingInstallation().isEmpty());
            assertTrue(w.button("backButton").isDisabled(), "the empty maintenance page is skipped on the way back");
        });
    }
}
