package de.yawi.installer.core.i18n;

import de.yawi.installer.core.manifest.Component;
import de.yawi.installer.core.manifest.InstallManifest;
import de.yawi.installer.core.manifest.TestManifests;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManifestMessagesTest {

    private static final InstallManifest BUNDLED = TestManifests.bundled();
    private static final ManifestMessages MESSAGES = new ManifestMessages(BUNDLED);

    private static Component component(String id) {
        return BUNDLED.component(id).orElseThrow();
    }

    @Test
    void resolvesTranslatedText() {
        assertEquals("Spieldateien", MESSAGES.componentName(Locale.GERMAN, component("core")));
        assertEquals("Server-Komponente zum Hosten eigener Partien.",
                MESSAGES.componentDescription(Locale.GERMAN, component("server")));
    }

    @Test
    void regionalLocaleFindsTheLanguage() {
        assertEquals("Dedizierter Server",
                MESSAGES.componentName(Locale.forLanguageTag("de-AT"), component("server")));
        assertEquals("Dedizierter Server",
                MESSAGES.componentName(Locale.forLanguageTag("de-DE-1996"), component("server")));
    }

    @Test
    void fallsBackToTheManifestText() {
        // English is not translated in installer.xml; the direct text applies.
        assertEquals("Game files", MESSAGES.componentName(Locale.ENGLISH, component("core")));
        assertEquals("Game files", MESSAGES.componentName(Locale.FRENCH, component("core")));
        assertEquals("Game files", MESSAGES.componentName(null, component("core")));
    }

    @Test
    void unknownPathYieldsTheFallback() {
        assertEquals("x", MESSAGES.resolve(Locale.GERMAN, "components.nope.name", "x"));
        assertNull(MESSAGES.resolve(Locale.GERMAN, "components.nope.description", null));
    }

    @Test
    void inputAndOptionLabelsResolveByPath() {
        InstallManifest full = TestManifests.full();
        Component server = full.component("server").orElseThrow();
        var mode = server.inputs().stream().filter(i -> i.id().equals("mode")).findFirst().orElseThrow();
        Map<Locale, Map<String, String>> texts = Map.of(Locale.GERMAN, Map.of(
                "components.server.inputs.mode.label", "Modus",
                "components.server.inputs.mode.options.lan", "Nur LAN"));
        ManifestMessages m = new ManifestMessages(texts, Set.of(
                "components.server.inputs.mode.label", "components.server.inputs.mode.options.lan"));
        assertEquals("Modus", m.inputLabel(Locale.GERMAN, server, mode));
        assertEquals("Mode", m.inputLabel(Locale.ENGLISH, server, mode));
        assertEquals("Nur LAN", m.optionLabel(Locale.GERMAN, server, mode, mode.options().get(0)));
        assertEquals("Internet", m.optionLabel(Locale.GERMAN, server, mode, mode.options().get(1)));
        // The paths are known to the manifest-backed instance, so no warning is logged for them.
        assertEquals("Mode", new ManifestMessages(full).inputLabel(Locale.GERMAN, server, mode));
    }

    @Test
    void presetNamesComeFromTheManifestOrTheFallback() {
        Map<Locale, Map<String, String>> texts = Map.of(
                Locale.GERMAN, Map.of("presets.full.name", "Alles"));
        ManifestMessages m = new ManifestMessages(texts, Set.of("presets.full.name"));
        assertEquals("Alles", m.presetName(Locale.GERMAN, "full", "Full"));
        assertEquals("Full", m.presetName(Locale.ENGLISH, "full", "Full"));
        assertEquals("typical", m.presetName(Locale.GERMAN, "typical", "typical"));
        assertEquals("presets.full.name", ManifestMessages.presetPath("full"));
    }

    @Test
    void missingDescriptionStaysNull() {
        Component bare = new Component("bare", false, false, -1, "Bare", null,
                List.of(), List.of(), List.of(), List.of());
        assertNull(MESSAGES.componentDescription(Locale.GERMAN, bare));
    }

    @Test
    void exactLocaleWinsOverLanguage() {
        Map<Locale, Map<String, String>> texts = Map.of(
                Locale.forLanguageTag("de"), Map.of("components.a.name", "Deutsch"),
                Locale.forLanguageTag("de-CH"), Map.of("components.a.name", "Schweizerdeutsch"));
        ManifestMessages m = new ManifestMessages(texts, Set.of("components.a.name"));
        assertEquals("Schweizerdeutsch", m.resolve(Locale.forLanguageTag("de-CH"), "components.a.name", "?"));
        assertEquals("Deutsch", m.resolve(Locale.forLanguageTag("de-AT"), "components.a.name", "?"));
        assertEquals("Deutsch", m.resolve(Locale.GERMAN, "components.a.name", "?"));
    }

    @Test
    void fullExampleResolvesBothLanguages() {
        ManifestMessages full = new ManifestMessages(TestManifests.full());
        Component server = TestManifests.full().component("server").orElseThrow();
        assertEquals("Dedicated Server", full.componentName(Locale.ENGLISH, server));
        assertEquals("Dedizierter Server", full.componentName(Locale.GERMAN, server));
    }
}
