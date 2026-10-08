package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OperatingSystemTest {

    @Test
    void manifestNamesRoundTrip() {
        for (OperatingSystem os : OperatingSystem.values()) {
            os.manifestName().ifPresent(name ->
                    assertEquals(os, OperatingSystem.fromManifestName(name)));
        }
        assertEquals(Optional.empty(), OperatingSystem.UNKNOWN.manifestName());
    }

    @Test
    void unknownNamesMapToUnknown() {
        assertEquals(OperatingSystem.UNKNOWN, OperatingSystem.fromManifestName("solaris"));
        assertEquals(OperatingSystem.UNKNOWN, OperatingSystem.fromManifestName("Linux"));
        assertEquals(OperatingSystem.UNKNOWN, OperatingSystem.fromManifestName(null));
    }
}
