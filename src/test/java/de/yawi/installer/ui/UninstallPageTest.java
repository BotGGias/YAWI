package de.yawi.installer.ui;

import de.yawi.installer.core.engine.Engine;
import de.yawi.installer.core.engine.Uninstaller;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import de.yawi.installer.core.state.InstallationRegistry;
import de.yawi.installer.core.state.RecordWriter;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.ProgressPageController;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E14-S03: "Uninstall" on the maintenance page leads straight to the uninstall
 * page, then to the progress page which removes the installation, and the
 * finish page reports it. Runs where a display is available.
 */
class UninstallPageTest {

    private static final String PRODUCT = "your-product";
    private static final Instant FINISHED = Instant.parse("2026-09-14T10:00:00Z");

    @TempDir
    Path tmp;

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private InstallationRegistry registry() {
        return InstallationRegistry.at(tmp.resolve("registry"), PRODUCT);
    }

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

    /** An installation with two recorded files in a recorded folder, finished at {@link #FINISHED}. */
    private void installed(Path destination) throws IOException {
        InstallationRecord record = new InstallationRecord(PRODUCT, "0.1.0", FINISHED.minusSeconds(60), destination,
                List.of("core"), Map.of());
        try (RecordWriter writer = RecordWriter.open(RecordWriter.defaultFile(destination), record)) {
            Files.createDirectories(destination.resolve("data"));
            record.directoryCreated(destination.resolve("data"));
            write(destination.resolve("data/a.bin"), "a\n", FINISHED.minusSeconds(30));
            record.fileCreated(destination.resolve("data/a.bin"));
            write(destination.resolve("settings.ini"), "port=1\n", FINISHED.minusSeconds(30));
            record.fileCreated(destination.resolve("settings.ini"));
            record.runFinished(FINISHED);
        }
        registry().register(record);
    }

    private static void write(Path file, String content, Instant modified) throws IOException {
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    private static String text(Wizard w, String fxId) {
        return ((Labeled) w.root().lookup("#" + fxId)).getText();
    }

    private static Node node(Wizard w, String fxId) {
        return w.root().lookup("#" + fxId);
    }

    @Test
    void uninstallSkipsTheManifestPagesAndRemovesTheInstallationKeepingWhatTheUserChanged() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("YOUR INSTALLER"));
        installed(dest);
        // The user edited a shipped file and added one of their own.
        write(dest.resolve("settings.ini"), "port=2\n", FINISHED.plusSeconds(600));
        write(dest.resolve("saves.dat"), "my game\n", FINISHED.plusSeconds(600));

        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            assertEquals("maintenance", w.flow().getCurrentPage().name());
            assertEquals("uninstall", w.flow().getPages().get(1).name(), "right behind the maintenance page");
            ((RadioButton) node(w, "modeUninstall")).setSelected(true);
            w.advance();

            assertEquals("uninstall", w.flow().getCurrentPage().name(), "welcome … summary are skipped");
            assertEquals(InstallerModel.InstallMode.UNINSTALL, w.model().getInstallMode());
            assertEquals("Uninstall", w.pageTitle());
            assertEquals("Uninstall", w.button("nextButton").getText());
            assertFalse(w.button("backButton").isDisabled(), "back to the maintenance page is possible");
            assertEquals("YOUR INSTALLER 0.1.0 will be removed from " + dest.toAbsolutePath().normalize() + ".",
                    text(w, "heading"));
            assertEquals("2 installed file(s) and the folders they leave empty will be deleted.", text(w, "count"));
            assertTrue(node(w, "modifiedBox").isVisible());
            assertEquals("Also delete 1 file(s) that changed since the installation", text(w, "deleteModified"));
            assertFalse(((CheckBox) node(w, "deleteModified")).isSelected(), "safe default");
            assertTrue(node(w, "userDataBox").isVisible());
            assertEquals("Also delete 1 other file(s) in the folder that the installer did not put there",
                    text(w, "deleteUserData"));
            assertFalse(((CheckBox) node(w, "deleteUserData")).isSelected(), "safe default");
            assertFalse(node(w, "noReverse").isVisible(), "no run-command ran");

            w.advance();
            assertEquals("progress", w.flow().getCurrentPage().name());
            assertEquals("Uninstalling", w.pageTitle());
            assertTrue(w.button("backButton").isDisabled(), "no way back");
            Engine.Result result = WizardNavigationTest.await(((ProgressPageController) w.flow().getCurrentPage()).result());

            assertTrue(result.succeeded(), result.failure().map(Throwable::getMessage).orElse(""));
            Uninstaller.Report report = w.model().getUninstallReport().orElseThrow();
            assertTrue(report.clean());
            assertEquals(List.of("settings.ini"), report.keptModified());
            assertEquals(List.of("saves.dat"), report.keptUnrecorded());
            assertEquals("The installation was removed; 2 file(s) stayed as you chose.", text(w, "outcome"));
            assertFalse(w.button("nextButton").isDisabled());

            w.advance();
            assertEquals("finish", w.flow().getCurrentPage().name());
            assertEquals("Finish", w.button("nextButton").getText());
            assertTrue(WizardNavigationTest.visibleTexts(w.root()).contains("YOUR INSTALLER has been removed."));
            assertFalse(node(w, "launch").isVisible(), "nothing to launch");
            assertFalse(node(w, "saveAnswers").isVisible());
            assertTrue(Diagnostics.report(w.model(), java.util.Optional.empty()).contains("kept (changed since the installation): settings.ini"));
        });

        assertFalse(Files.exists(dest.resolve("data")), "recorded file and its folder are gone");
        assertFalse(Files.exists(dest.resolve(".installer")), "record and backups are gone");
        assertEquals("port=2\n", Files.readString(dest.resolve("settings.ini")), "changed file kept");
        assertTrue(Files.exists(dest.resolve("saves.dat")), "user file kept");
        assertTrue(registry().list().isEmpty(), "unregistered");
    }

    @Test
    void withBothBoxesTickedTheWholeFolderGoes() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("YOUR INSTALLER"));
        installed(dest);
        write(dest.resolve("settings.ini"), "port=2\n", FINISHED.plusSeconds(600));
        write(dest.resolve("saves.dat"), "my game\n", FINISHED.plusSeconds(600));

        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            ((RadioButton) node(w, "modeUninstall")).setSelected(true);
            w.advance();
            ((CheckBox) node(w, "deleteModified")).setSelected(true);
            ((CheckBox) node(w, "deleteUserData")).setSelected(true);
            assertEquals(Uninstaller.Options.PURGE, w.model().uninstallOptions());
            w.advance();
            Engine.Result result = WizardNavigationTest.await(((ProgressPageController) w.flow().getCurrentPage()).result());
            assertTrue(result.succeeded());
            assertEquals("The installation was removed.", text(w, "outcome"));
            assertTrue(w.model().getUninstallReport().orElseThrow().destinationRemoved());
        });

        assertFalse(Files.exists(dest));
        assertTrue(registry().list().isEmpty());
    }

    @Test
    void goingBackFromTheUninstallPageAndChoosingModifyRunsTheNormalWizard() throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("YOUR INSTALLER"));
        installed(dest);

        WizardNavigationTest.onFxThread(() -> {
            Wizard w = wizard(TestManifests.bundled());
            ((RadioButton) node(w, "modeUninstall")).setSelected(true);
            w.advance();
            assertEquals("uninstall", w.flow().getCurrentPage().name());
            w.flow().back();
            assertEquals("maintenance", w.flow().getCurrentPage().name());
            ((RadioButton) node(w, "modeModify")).setSelected(true);
            w.advance();
            assertEquals("welcome", w.flow().getCurrentPage().name(), "the uninstall page steps aside again");
            assertEquals(InstallerModel.InstallMode.MODIFY, w.model().getInstallMode());
            assertEquals("Welcome", w.pageTitle());
        });
        assertTrue(Files.exists(dest.resolve("data/a.bin")), "nothing touched");
    }
}
