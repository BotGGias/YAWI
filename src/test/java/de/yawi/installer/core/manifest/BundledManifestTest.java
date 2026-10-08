package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E01-S05-T02: the manifest shipped in src/main/resources loads and looks like the real product. */
class BundledManifestTest {

    private final InstallManifest manifest = TestManifests.bundled();

    @Test
    void loadsAndValidates() {
        assertEquals("your-product", manifest.product().id());
        assertEquals("YOUR INSTALLER", manifest.product().name());
        assertTrue(new ManifestValidator().validate(manifest).isEmpty(), "bundled manifest has warnings");
    }

    @Test
    void keepsTheEightLanguagesAndThePortOfTheOldUi() {
        assertEquals(8, manifest.languages().languages().size());
        assertTrue(manifest.languages().languages().contains(Locale.GERMAN));
        assertEquals(Locale.ENGLISH, manifest.languages().defaultLanguage());

        ComponentInput port = manifest.component("server").orElseThrow().inputs().stream()
                .filter(i -> i.id().equals("serverPort")).findFirst().orElseThrow();
        assertEquals("50000", port.defaultValue());
        assertEquals(ComponentInput.InputType.INT, port.type());
    }

    @Test
    void windowSizeFallsBackToTheDefaults() {
        assertEquals(WizardConfig.DEFAULT_WIDTH, manifest.wizard().width());
        assertEquals(WizardConfig.DEFAULT_HEIGHT, manifest.wizard().height());
    }

    @Test
    void hasAtLeastTwoComponentsAndAllProviderTypes() {
        assertTrue(manifest.components().size() >= 2);
        assertTrue(manifest.components().stream().anyMatch(Component::required));

        Source source = manifest.sources().get(0);
        assertTrue(source.hasProviderOfType(Provider.Bundled.class));
        assertTrue(source.hasProviderOfType(Provider.Http.class));
        assertTrue(source.hasProviderOfType(Provider.Torrent.class));
    }

    @Test
    void hasARunCommandStepForEveryOperatingSystem() {
        InstallStep.RunCommand run = manifest.steps().stream()
                .filter(InstallStep.RunCommand.class::isInstance)
                .map(InstallStep.RunCommand.class::cast)
                .findFirst().orElseThrow();
        for (OperatingSystem os : new OperatingSystem[]{OperatingSystem.LINUX, OperatingSystem.WINDOWS, OperatingSystem.MACOS}) {
            assertFalse(run.commandsFor(os).isEmpty(), "no commands for " + os);
        }
    }

    @Test
    void everyReferencedStepAndSourceResolves() {
        for (Component c : manifest.components()) {
            for (OsRef step : c.stepRefs()) {
                assertTrue(manifest.step(step.ref()).isPresent(), step.ref());
            }
            for (OsRef source : c.uses()) {
                assertTrue(manifest.source(source.ref()).isPresent(), source.ref());
            }
        }
        assertEquals(5, manifest.stepsFor(manifest.resolveSelection(java.util.Set.of("server")),
                OperatingSystem.LINUX).size());
    }
}
