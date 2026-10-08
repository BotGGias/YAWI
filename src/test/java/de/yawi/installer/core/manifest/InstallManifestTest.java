package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallManifestTest {

    private final InstallManifest manifest = TestManifests.full();

    @Test
    void lookupsFindById() {
        assertTrue(manifest.component("server").isPresent());
        assertTrue(manifest.source("base-data").isPresent());
        assertTrue(manifest.step("register-service").isPresent());
        assertTrue(manifest.preset("full").isPresent());
        assertTrue(manifest.component("nope").isEmpty());
    }

    @Test
    void selectionAlwaysContainsRequiredComponents() {
        assertEquals(Set.of("core"), manifest.resolveSelection(Set.of()));
    }

    @Test
    void selectionPullsInDependenciesInManifestOrder() {
        // "server" depends on "core"; core is listed first in the manifest
        assertEquals(List.of("core", "server"), List.copyOf(manifest.resolveSelection(Set.of("server"))));
    }

    @Test
    void stepsFollowComponentOrderWithoutDuplicates() {
        List<String> ids = manifest.stepsFor(Set.of("server", "core"), OperatingSystem.LINUX).stream()
                .map(InstallStep::id).toList();
        assertEquals(List.of("unpack-core", "configure-core", "install-server", "register-service"), ids);

        assertEquals(List.of("unpack-core", "configure-core"),
                manifest.stepsFor(Set.of("core"), OperatingSystem.LINUX).stream().map(InstallStep::id).toList());
    }

    @Test
    void sourcesForSelectionAreDeduplicated() {
        // both components use base-data
        assertEquals(1, manifest.sourcesFor(Set.of("core", "server"), OperatingSystem.LINUX).size());
        assertTrue(manifest.sourcesFor(Set.of(), OperatingSystem.LINUX).isEmpty());
    }

    @Test
    void modelIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> manifest.components().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.component("server").orElseThrow().dependsOn().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.messages().clear());
    }
}
