package de.yawi.installer.ui;

import de.yawi.installer.core.download.ProviderKind;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.ManifestParser;
import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.ui.WizardNavigationTest.Wizard;
import de.yawi.installer.ui.page.SourcePageController;
import javafx.scene.control.Labeled;
import javafx.scene.control.RadioButton;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E07-S03: the source page through the real wizard, version check against the local server. */
class SourcePageTest {

    private static de.yawi.installer.core.download.TestHttpServerAccess server;

    @BeforeAll
    static void start() throws Exception {
        WizardNavigationTest.startToolkit();
        server = new de.yawi.installer.core.download.TestHttpServerAccess();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** The version-check manifest with its URLs pointing at the local server and the given version endpoint. */
    private static InstallManifest manifest(String versionPath) {
        String xml = new String(TestManifests.bytes("/manifest/download/version-check.xml"), StandardCharsets.UTF_8)
                .replace("__BASE__", server.base())
                .replace("__VERSION__", versionPath);
        return new ManifestParser().parse(xml.getBytes(StandardCharsets.UTF_8), TestManifests.origin("/manifest/download/version-check.xml"));
    }

    private static RadioButton radio(Wizard w, String sourceId, ProviderKind kind) {
        return (RadioButton) w.root().lookup("#source-" + sourceId + "-" + kind.name().toLowerCase(Locale.ROOT));
    }

    private static String versionLine(Wizard w, String sourceId) {
        return ((Labeled) w.root().lookup("#version-" + sourceId)).getText();
    }

    private static SourcePageController page(Wizard w) {
        return (SourcePageController) w.flow().getCurrentPage();
    }

    @Test
    void skippedWhenThereIsNothingToChoose() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            // The bundled manifest offers bundled + http for game-data: the page shows.
            Wizard w = WizardNavigationTest.wizard(TestManifests.bundled(), Locale.ENGLISH);
            w.advanceTo("summary");
            assertTrue(w.flow().getPages().stream().anyMatch(p -> p.name().equals("source") && !p.isSkippable()));

            // Only bundled providers: nothing to choose, the page is stepped over.
            Wizard only = WizardNavigationTest.wizard(TestManifests.parse("/manifest/engine/file-steps.xml"), Locale.ENGLISH);
            assertTrue(only.flow().getPages().stream().noneMatch(p -> p.name().equals("source")), "manifest lists no source page");
        });
    }

    @Test
    void offersTheWaysAndWritesTheChoice() throws Exception {
        server.custom("1.0.0");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(manifest("custom"), Locale.ENGLISH);
            w.advanceTo("source");
            WizardNavigationTest.await(page(w).versionChecksDone());

            assertTrue(radio(w, "data", ProviderKind.BUNDLED).isSelected(), "shipped copy is the default");
            assertFalse(radio(w, "data", ProviderKind.HTTP).isSelected());
            assertFalse(radio(w, "data", ProviderKind.TORRENT).isDisabled(), "torrent is a way since E08");
            assertEquals("BitTorrent opens port 6881 on this computer and shares the downloaded data with other users while the installation runs.",
                    ((Labeled) w.root().lookup("#torrent-hint-data")).getText());
            assertNull(w.root().lookup("#torrent-hint-unverified"), "no torrent provider, no hint");
            assertNull(w.root().lookup("#source-only-bundled"), "one way only: no block");
            assertEquals("The shipped copy (1.0.0) is up to date.", versionLine(w, "data"));
            assertEquals(Optional.of(Optional.of("1.0.0")), page(w).onlineVersion("data"));

            radio(w, "data", ProviderKind.HTTP).setSelected(true);
            assertEquals(ProviderKind.HTTP, w.model().getSourceChoices().get("data"));

            w.model().setLocale(Locale.GERMAN);
            assertEquals("Aus dem Internet laden", radio(w, "data", ProviderKind.HTTP).getText());
            assertEquals("Die mitgelieferte Kopie (1.0.0) ist aktuell.", versionLine(w, "data"));

            // E10-S01: the source without sha256 warns where the download is chosen, the others do not.
            assertNull(w.root().lookup("#unverified-data"), "data has a checksum");
            assertEquals("Das Manifest enthält keine Prüfsumme für diesen Download; er kann nach dem Laden nicht geprüft werden.",
                    ((Labeled) w.root().lookup("#unverified-unverified")).getText());
            radio(w, "unverified", ProviderKind.HTTP).setSelected(true);

            w.advanceTo("summary");
            List<String> texts = WizardNavigationTest.visibleTexts(w.root());
            assertTrue(texts.contains("data (Download), only-bundled (mitgelieferte Kopie), unverified (Download, nicht prüfbar)"),
                    texts.toString());
        });
    }

    @Test
    void newerVersionOnlinePreselectsTheDownloadUnlessTheUserChose() throws Exception {
        server.custom("{\"version\":\"1.1.0\"}");
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(manifest("custom"), Locale.ENGLISH);
            w.advanceTo("source");
            assertEquals("Checking for a newer version…", versionLine(w, "data"));
            WizardNavigationTest.await(page(w).versionChecksDone());

            assertTrue(radio(w, "data", ProviderKind.HTTP).isSelected(), "newer online: download preselected");
            assertEquals(ProviderKind.HTTP, w.model().getSourceChoices().get("data"));
            assertEquals("Version 1.1.0 is available online; the shipped copy is 1.0.0. The download is preselected.",
                    versionLine(w, "data"));

            // The user insists on the shipped copy; re-entering the page runs the check again but respects that.
            radio(w, "data", ProviderKind.BUNDLED).setSelected(true);
            w.flow().back();
            w.advanceTo("source");
            WizardNavigationTest.await(page(w).versionChecksDone());
            assertTrue(radio(w, "data", ProviderKind.BUNDLED).isSelected());
            assertEquals(ProviderKind.BUNDLED, w.model().getSourceChoices().get("data"));
        });
    }

    @Test
    void unreachableVersionCheckFailsQuietly() throws Exception {
        WizardNavigationTest.onFxThread(() -> {
            Wizard w = WizardNavigationTest.wizard(manifest("missing"), Locale.ENGLISH);
            w.advanceTo("source");
            WizardNavigationTest.await(page(w).versionChecksDone());
            assertTrue(versionLine(w, "data").startsWith("The version check is not reachable"), versionLine(w, "data"));
            assertTrue(radio(w, "data", ProviderKind.BUNDLED).isSelected());
            assertFalse(w.button("nextButton").isDisabled());
        });
    }
}
