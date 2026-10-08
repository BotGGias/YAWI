package de.yawi.installer.ui;

import de.yawi.installer.core.manifest.TestManifests;
import de.yawi.installer.core.manifest.WizardConfig;
import de.yawi.installer.ui.page.WizardPageController;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registrations only; actual loading needs a toolkit and is covered by WizardNavigationTest. */
class PageRegistryTest {

    @Test
    void registersExactlyTheKnownPagesPlusTheImplicitOnes() {
        Set<String> expected = new HashSet<>(WizardConfig.KNOWN_PAGES);
        expected.addAll(WizardConfig.IMPLICIT_PAGES);
        assertEquals(expected, PageRegistry.registeredNames());
    }

    @Test
    void everyRegistrationPointsToAnExistingFxmlAndAConcreteController() throws Exception {
        for (String name : PageRegistry.registeredNames()) {
            PageRegistry.Registration r = PageRegistry.registration(name);
            assertNotNull(PageRegistry.class.getResource(PageRegistry.FXML_BASE + r.fxml()),
                    name + ": FXML not found: " + r.fxml());
            assertFalse(Modifier.isAbstract(r.controller().getModifiers()), name + ": controller is abstract");
            assertTrue(WizardPageController.class.isAssignableFrom(r.controller()));
            assertNotNull(r.controller().getConstructor(InstallerModel.class),
                    name + ": controller needs an (InstallerModel) constructor");
        }
    }

    @Test
    void everyPageOfTheBundledManifestIsRegistered() {
        for (String name : TestManifests.bundled().wizard().pages()) {
            assertNotNull(PageRegistry.registration(name));
        }
    }

    @Test
    void unknownNamesAreAProgrammingError() {
        assertThrows(IllegalArgumentException.class, () -> PageRegistry.registration("nope"));
    }
}
