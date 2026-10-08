package de.yawi.installer.ui.i18n;

import de.yawi.installer.core.i18n.ManifestMessages;
import de.yawi.installer.core.i18n.Messages;
import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import javafx.beans.binding.StringBinding;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Properties and bindings work without a JavaFX toolkit, so this runs headless. */
class LocaleManagerTest {

    private static final InstallManifest MANIFEST = TestManifests.bundled();

    private static LocaleManager manager() {
        return new LocaleManager(Messages.load(), new ManifestMessages(MANIFEST), Locale.ENGLISH);
    }

    @Test
    void manifestTextsFollowTheLocale() {
        LocaleManager i18n = manager();
        Component core = MANIFEST.component("core").orElseThrow();
        StringBinding name = i18n.componentName(core);
        StringBinding description = i18n.componentDescription(core);
        StringBinding byPath = i18n.manifestText("components.server.name", "fallback");
        assertEquals("Game files", name.get());
        assertEquals("Base data without which nothing runs.", description.get());
        assertEquals("fallback", byPath.get()); // installer.xml translates only de

        i18n.setLocale(Locale.GERMAN);
        assertEquals("Spieldateien", name.get());
        assertEquals("Basisdaten, ohne die nichts laeuft.", description.get());
        assertEquals("Dedizierter Server", byPath.get());

        i18n.setLocale(Locale.FRENCH); // not translated in the manifest
        assertEquals("Game files", name.get());
    }

    @Test
    void componentDescriptionMayBeNull() {
        Component bare = new Component("bare", false, false, -1, "Bare", null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertNull(manager().componentDescription(bare).get());
    }

    @Test
    void textFollowsTheLocale() {
        LocaleManager i18n = manager();
        StringBinding next = i18n.text("wizard.next");
        assertEquals("Next", next.get());

        i18n.setLocale(Locale.GERMAN);
        assertEquals("Weiter", next.get());

        i18n.setLocale(Locale.FRENCH); // no translation yet
        assertEquals("Next", next.get());
    }

    @Test
    void textSubstitutesArguments() {
        LocaleManager i18n = manager();
        StringBinding title = i18n.text("welcome.title", "YOUR INSTALLER");
        assertEquals("Welcome to the YOUR INSTALLER installer", title.get());

        i18n.setLocale(Locale.GERMAN);
        assertEquals("Willkommen zum YOUR INSTALLER-Installer", title.get());
    }

    @Test
    void getIsOneShotInTheCurrentLanguage() {
        LocaleManager i18n = manager();
        i18n.setLocale(Locale.GERMAN);
        assertEquals("Abbrechen", i18n.get("wizard.cancel"));
    }

    @Test
    void markerBundleYieldsMarkedKeysAndNeverThrows() {
        ResourceBundle bundle = manager().markerBundle();
        String value = bundle.getString("wizard.next");
        assertTrue(I18nFxml.isMarker(value));
        assertEquals("wizard.next", value.substring(1));
        assertEquals(I18nFxml.MARKER + "no.such.key", bundle.getString("no.such.key"));
        assertTrue(bundle.containsKey("anything"));
        assertFalse(bundle.getKeys().hasMoreElements());
    }

    @Test
    void isMarkerRecognisesOnlyMarkedText() {
        assertFalse(I18nFxml.isMarker(null));
        assertFalse(I18nFxml.isMarker(""));
        assertFalse(I18nFxml.isMarker("Next"));
        assertTrue(I18nFxml.isMarker(I18nFxml.MARKER + "wizard.next"));
    }
}
