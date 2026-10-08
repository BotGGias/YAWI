package de.yawi.installer.core.manifest;

import de.yawi.installer.core.manifest.ComponentInput.InputType;
import de.yawi.installer.core.manifest.ComponentInput.Option;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputValidatorTest {

    private static final ComponentInput PORT =
            new ComponentInput("port", InputType.INT, "50000", 1024L, 65535L, "Port", false, List.of());
    private static final ComponentInput NAME =
            new ComponentInput("name", InputType.STRING, null, null, null, "Name", false, List.of());
    private static final ComponentInput NICK =
            new ComponentInput("nick", InputType.STRING, null, null, null, "Nick", true, List.of());
    private static final ComponentInput AUTOSTART =
            new ComponentInput("autostart", InputType.BOOL, null, null, null, "Autostart", false, List.of());
    private static final ComponentInput MODE = new ComponentInput("mode", InputType.CHOICE, "lan", null, null,
            "Mode", true, List.of(new Option("lan", "LAN"), new Option("internet", "Internet")));

    private static String key(ComponentInput input, String value) {
        return InputValidator.validate(input, value).map(InputValidator.Problem::key).orElse("ok");
    }

    @Test
    void integersRespectBounds() {
        assertEquals("ok", key(PORT, "50000"));
        assertEquals("ok", key(PORT, " 1024 "));
        assertEquals(InputValidator.TOO_SMALL, key(PORT, "1023"));
        assertEquals(InputValidator.TOO_LARGE, key(PORT, "65536"));
        assertEquals(InputValidator.NOT_A_NUMBER, key(PORT, "abc"));
        assertEquals(InputValidator.NOT_A_NUMBER, key(PORT, "1.5"));
        assertEquals(InputValidator.REQUIRED, key(PORT, ""), "a number is never optional");

        Optional<InputValidator.Problem> tooSmall = InputValidator.validate(PORT, "1");
        assertArrayEquals(new Object[]{"1024"}, tooSmall.orElseThrow().args());
    }

    @Test
    void stringsAreOnlyCheckedForPresence() {
        assertEquals("ok", key(NAME, ""));
        assertEquals("ok", key(NAME, null));
        assertEquals("ok", key(NAME, "anything"));
        assertEquals(InputValidator.REQUIRED, key(NICK, "   "));
        assertEquals("ok", key(NICK, "x"));
    }

    @Test
    void booleansAndChoicesMustMatch() {
        assertEquals("ok", key(AUTOSTART, "true"));
        assertEquals("ok", key(AUTOSTART, "false"));
        assertEquals(InputValidator.NOT_A_BOOLEAN, key(AUTOSTART, "yes"));
        assertEquals(InputValidator.REQUIRED, key(AUTOSTART, ""));
        assertEquals("ok", key(MODE, "internet"));
        assertEquals(InputValidator.NOT_AN_OPTION, key(MODE, "wan"));
        assertEquals(InputValidator.REQUIRED, key(MODE, ""));
    }

    @Test
    void defaultsFallBackByType() {
        assertEquals("50000", InputValidator.defaultValue(PORT));
        assertEquals("", InputValidator.defaultValue(NAME));
        assertEquals("false", InputValidator.defaultValue(AUTOSTART));
        assertEquals("lan", InputValidator.defaultValue(MODE));
        ComponentInput choiceNoDefault = new ComponentInput("m", InputType.CHOICE, null, null, null, "M", false,
                List.of(new Option("a", "A"), new Option("b", "B")));
        assertEquals("a", InputValidator.defaultValue(choiceNoDefault));
        assertTrue(InputValidator.validate(MODE, InputValidator.defaultValue(MODE)).isEmpty());
    }
}
