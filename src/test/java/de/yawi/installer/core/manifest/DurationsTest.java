package de.yawi.installer.core.manifest;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E08: manifest durations, and the XSD pattern that must match them. */
class DurationsTest {

    @Test
    void parsesAndFormats() {
        assertEquals(Duration.ofSeconds(90), Durations.parse("90s"));
        assertEquals(Duration.ofMinutes(2), Durations.parse(" 2m "));
        assertEquals(Duration.ofMillis(500), Durations.parse("500ms"));
        assertEquals("90s", Durations.format(Duration.ofSeconds(90)));
        assertEquals("2m", Durations.format(Duration.ofMinutes(2)));
        assertEquals("500ms", Durations.format(Duration.ofMillis(500)));
        assertEquals("1m", Durations.format(Duration.ofSeconds(60)));
    }

    @Test
    void rejectsWhatTheSchemaRejects() {
        for (String bad : new String[] {"", "90", "s", "1.5s", "2h", "-1s"}) {
            assertThrows(IllegalArgumentException.class, () -> Durations.parse(bad), bad);
        }
    }

    @Test
    void schemaPatternMatchesTheParser() {
        String xsd = new String(TestManifests.bytes("/installer-manifest-1.xsd"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(xsd.contains("<xs:pattern value=\"" + Durations.PATTERN.pattern().replace("([0-9]+)", "[0-9]+") + "\"/>"),
                "XSD duration pattern must equal Durations.PATTERN");
    }
}
