package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S03: the reg-command and value maths for the Windows PATH. */
class WindowsPathRegistryTest {

    @Test
    void keyDependsOnScope() {
        assertEquals("HKCU\\Environment", WindowsPathRegistry.environmentKey(false));
        assertTrue(WindowsPathRegistry.environmentKey(true).startsWith("HKLM\\SYSTEM"));
    }

    @Test
    void parsesThePathValueOutOfRegQueryOutput() {
        String out = "\r\nHKEY_CURRENT_USER\\Environment\r\n"
                + "    Path    REG_EXPAND_SZ    C:\\Windows;C:\\Tools\r\n\r\n";
        assertEquals(Optional.of("C:\\Windows;C:\\Tools"), WindowsPathRegistry.parseValue(out));
    }

    @Test
    void parseReturnsEmptyWhenTheValueIsAbsent() {
        assertEquals(Optional.empty(), WindowsPathRegistry.parseValue("HKEY_CURRENT_USER\\Environment\r\n"));
    }

    @Test
    void missingIgnoresDirectoriesAlreadyPresentCaseInsensitively() {
        List<String> missing = WindowsPathRegistry.missing("C:\\Windows;C:\\su\\bin",
                List.of("C:\\SU\\BIN", "C:\\su\\tools"));
        assertEquals(List.of("C:\\su\\tools"), missing);
    }

    @Test
    void appendedAddsOnlyTheMissingOnes() {
        assertEquals(Optional.of("C:\\Windows;C:\\su\\bin"),
                WindowsPathRegistry.appended("C:\\Windows", List.of("C:\\su\\bin")));
        assertEquals(Optional.empty(),
                WindowsPathRegistry.appended("C:\\su\\bin", List.of("C:\\su\\bin")), "nothing to add");
    }

    @Test
    void removedTakesOutExactlyOurDirectories() {
        assertEquals(Optional.of("C:\\Windows"),
                WindowsPathRegistry.removed("C:\\Windows;C:\\su\\bin", List.of("C:\\SU\\bin")));
        assertEquals(Optional.empty(),
                WindowsPathRegistry.removed("C:\\Windows", List.of("C:\\su\\bin")), "nothing to remove");
    }

    @Test
    void addCommandWritesAnExpandableString() {
        List<String> cmd = WindowsPathRegistry.addCommand("C:\\x", false);
        assertTrue(cmd.contains("REG_EXPAND_SZ"), cmd.toString());
        assertTrue(cmd.contains("Path"), cmd.toString());
        assertTrue(cmd.contains("C:\\x"), cmd.toString());
    }
}
