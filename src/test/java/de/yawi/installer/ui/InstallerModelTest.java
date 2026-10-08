package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.platform.Architecture;
import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.LinuxPlatform;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import de.yawi.installer.core.state.InstallationRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerModelTest {

    private static InstallerModel model() {
        return new InstallerModel(TestManifests.bundled(), PlatformFactory.current());
    }

    @Test
    void offersTheLanguagesFromTheManifest() {
        List<Locale> languages = model().getAvailableLanguages();
        assertEquals(8, languages.size());
        assertTrue(languages.contains(Locale.GERMAN));
        assertTrue(languages.contains(Locale.ENGLISH));
    }

    @Test
    void everyLanguageHasADisplayNameInItsOwnLanguage() {
        for (Locale locale : model().getAvailableLanguages()) {
            String shown = locale.getDisplayLanguage(locale);
            assertTrue(shown != null && !shown.isBlank(),
                    "no display name for " + locale.toLanguageTag());
        }
    }

    @Test
    void preselectsAnOfferedLanguage() {
        InstallerModel model = model();
        Locale selected = model.getLocale();
        assertTrue(model.getAvailableLanguages().contains(selected),
                "preselected language " + selected + " is not offered");
    }

    @Test
    void exposesTheManifest() {
        assertEquals("your-product", model().getManifest().product().id());
    }

    @Test
    void localeIsObservable() {
        InstallerModel model = model();
        Locale[] seen = new Locale[1];
        model.localeProperty().addListener((observable, old, current) -> seen[0] = current);

        model.setLocale(Locale.JAPANESE);

        assertEquals(Locale.JAPANESE, seen[0]);
        assertEquals(Locale.JAPANESE, model.getLocale());
    }

    @Test
    void localeIsSharedWithTheLocaleManager() {
        InstallerModel model = model();
        assertNotNull(model.i18n());
        assertSame(model.i18n().localeProperty(), model.localeProperty());

        model.setLocale(Locale.GERMAN);

        assertEquals("Weiter", model.i18n().get("wizard.next"));
    }

    @Test
    void effectiveDestinationFallsBackToThePlatformDefault() {
        InstallerModel model = model();
        Path fallback = model.getEffectiveDestination();
        assertTrue(fallback.isAbsolute(), "resolved platform path: " + fallback);
        assertTrue(fallback.toString().contains("your-product"), fallback.toString());

        model.setDestination(Path.of("/opt/su"));
        assertEquals(Path.of("/opt/su"), model.getEffectiveDestination());
    }

    @Test
    void defaultDestinationFollowsTheScope() {
        InstallerModel model = model();
        Path user = model.defaultDestination(false);
        Path all = model.defaultDestination(true);
        assertTrue(user.isAbsolute() && all.isAbsolute());
        assertFalse(user.equals(all), user + " vs " + all);
        assertEquals(user, model.getEffectiveDestination());

        model.setSystemWide(true);
        assertEquals(all, model.getEffectiveDestination(), "no choice yet: the scope's default");

        model.setDestination(Path.of("/somewhere/else"));
        assertEquals(Path.of("/somewhere/else"), model.getEffectiveDestination(), "a choice wins");
        assertFalse(model.isElevationRequired(), "unset until the destination check ran");
    }

    @Test
    void shutdownRunsHooksLatestFirstAndSurvivesFailures() {
        InstallerModel model = model();
        List<String> ran = new java.util.ArrayList<>();
        model.onShutdown(() -> ran.add("first"));
        model.onShutdown(() -> { throw new IllegalStateException("boom"); });
        model.onShutdown(() -> ran.add("last"));

        model.shutdown();
        model.shutdown(); // hooks run once

        assertEquals(List.of("last", "first"), ran);
    }

    @Test
    void inputsCarryComponentValues() {
        InstallerModel model = model();
        assertTrue(model.getInputs().isEmpty());

        model.getInputs().put("serverPort", "50000");

        assertEquals("50000", model.getInputs().get("serverPort"));
    }

    @Test
    void destinationStartsUnsetAndIsWritable() {
        InstallerModel model = model();
        assertNull(model.getDestination());

        Path target = Path.of("/opt/your-product");
        model.setDestination(target);

        assertEquals(target, model.getDestination());
    }

    // --- E14-S02-T03: prefill from a previous installation's record ------------------------------

    private static final Platform LINUX = new LinuxPlatform(Architecture.X64, Environment.of(Map.of("HOME", "/home/me"),
            Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp")));

    private static InstallationRecord record(Path destination, List<String> components, Map<String, String> inputs) {
        return new InstallationRecord("your-product", "2.4.0", Instant.EPOCH, destination, components, inputs);
    }

    @Test
    void prefillTakesDestinationComponentsAndInputsFromTheRecord() throws Exception {
        InstallerModel model = new InstallerModel(TestManifests.full(), LINUX);
        model.getInputs().put("serverPort", "1234"); // whatever was there before loses

        model.prefillFrom(record(Path.of("/home/me/games/su"), List.of("server", "gone"),
                Map.of("serverPort", "50000", "mode", "internet")));

        assertEquals(Path.of("/home/me/games/su"), model.getDestination());
        assertFalse(model.isSystemWide(), "a folder in the user's home is a per-user installation");
        assertEquals(Set.of("core", "server"), model.getSelectedComponents(), "resolved: server depends on core");
        assertEquals("50000", model.getInputs().get("serverPort"));
        assertEquals("internet", model.getInputs().get("mode"));
        assertEquals(InstallerModel.InstallMode.FRESH, model.getInstallMode(), "the page sets the mode, not the prefill");
    }

    @Test
    void prefillFromASystemFolderSelectsAllUsers() throws Exception {
        InstallerModel model = new InstallerModel(TestManifests.full(), LINUX);
        model.getSelectedComponents().add("server");

        model.prefillFrom(record(Path.of("/opt/your-product"), List.of("core"), Map.of()));

        assertTrue(model.isSystemWide());
        assertEquals(Path.of("/opt/your-product"), model.getDestination());
        assertEquals(Set.of("core"), model.getSelectedComponents(), "a selection made before is replaced");
    }
}
