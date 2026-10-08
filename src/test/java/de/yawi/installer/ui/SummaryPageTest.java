package de.yawi.installer.ui;

import de.yawi.installer.core.elevation.ElevationNeed;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.SummaryPageController;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** The summary's administrator-rights row: shown when needed, commands in clear text, way back. */
class SummaryPageTest {

    @BeforeAll
    static void startToolkit() {
        WizardNavigationTest.startToolkit();
    }

    private static SummaryPageController summary(Wizard w) {
        return (SummaryPageController) w.flow().getCurrentPage();
    }

    private static String text(Wizard w, String fxId) {
        return ((Labeled) w.root().lookup("#" + fxId)).getText();
    }

    @Test
    void nothingIsShownWhenNothingNeedsRights() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("summary");
            assertEquals(ElevationNeed.NONE, summary(w).elevationNeed());
            assertFalse(w.root().lookup("#elevationBox").isVisible());
            assertFalse(w.root().lookup("#elevatedCommands").isVisible());
        });
    }

    @Test
    void elevatedStepsAreAnnouncedWithTheirCommandsAndTheSecretMasked() throws Exception {
        assumeFalse(de.yawi.installer.core.platform.PlatformFactory.current().isElevated());
        InstallManifest manifest = TestManifests.parse("/manifest/engine/elevated.xml");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(manifest, Locale.ENGLISH);
            w.advanceTo("components");
            ((TextField) w.root().lookup("#input-adminPassword")).setText("hunter2");
            w.advanceTo("summary");
            assertEquals(ElevationNeed.Mode.PARTIAL, summary(w).elevationNeed().mode());
            assertTrue(w.root().lookup("#elevationBox").isVisible());
            assertTrue(text(w, "elevation").contains("1 command(s)"), text(w, "elevation"));
            assertFalse(w.root().lookup("#changeDestination").isVisible(), "the folder is not the problem here");
            TitledPane commands = (TitledPane) w.root().lookup("#elevatedCommands");
            assertTrue(commands.isVisible());
            String listed = ((TextArea) commands.getContent()).getText();
            assertTrue(listed.startsWith("service: "), listed);
            assertFalse(listed.contains("hunter2"), "secret must be masked: " + listed);
            assertTrue(listed.contains("service.txt"), listed);
        });
    }

    @Test
    void aFolderThatNeedsRightsOffersTheWayBackToTheDestinationPage() throws Exception {
        assumeFalse(de.yawi.installer.core.platform.PlatformFactory.current().isElevated());
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("summary");
            // What the destination page found out (E06-S03) is all the summary goes by.
            w.model().setElevationRequired(true);
            summary(w).onEnter();
            assertEquals(ElevationNeed.Mode.WHOLE, summary(w).elevationNeed().mode());
            assertTrue(text(w, "elevation").contains(w.model().getEffectiveDestination().toString()), text(w, "elevation"));
            Hyperlink change = (Hyperlink) w.root().lookup("#changeDestination");
            assertTrue(change.isVisible());
            change.fire();
            assertEquals("destination", w.flow().getCurrentPage().name());
        });
    }
}
