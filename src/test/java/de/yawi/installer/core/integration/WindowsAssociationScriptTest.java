package de.yawi.installer.core.integration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S02: the reg commands that register (and remove) a Windows association. */
class WindowsAssociationScriptTest {

    private static AssociationSpec spec() {
        return new AssociationSpec("your-product", ".sumap", Path.of("C:\\Program Files\\SU\\bin\\yawi.exe"),
                Path.of("C:\\Program Files\\SU"), Optional.of("YOUR INSTALLER Map"));
    }

    @Test
    void perUserRootIsHkcuSystemWideIsHklm() {
        assertEquals("HKCU\\Software\\Classes", WindowsAssociationScript.classesRoot(false));
        assertEquals("HKLM\\Software\\Classes", WindowsAssociationScript.classesRoot(true));
    }

    @Test
    void addCommandsMapTheExtensionToTheProgIdAndTheOpenCommand() {
        List<List<String>> add = WindowsAssociationScript.addCommands(spec(), false);
        assertEquals(3, add.size());
        assertEquals(List.of("reg", "add", "HKCU\\Software\\Classes\\.sumap", "/ve", "/d",
                "your-installer.sumap", "/f"), add.get(0));
        assertEquals(List.of("reg", "add", "HKCU\\Software\\Classes\\your-installer.sumap", "/ve", "/d",
                "YOUR INSTALLER Map", "/f"), add.get(1));
        assertEquals(List.of("reg", "add", "HKCU\\Software\\Classes\\your-installer.sumap\\shell\\open\\command",
                "/ve", "/d", "\"C:\\Program Files\\SU\\bin\\yawi.exe\" \"%1\"", "/f"), add.get(2));
    }

    @Test
    void deleteCommandsRemoveTheProgIdAndTheExtensionDefault() {
        List<List<String>> del = WindowsAssociationScript.deleteCommands("your-product", ".sumap", true);
        assertEquals(List.of("reg", "delete", "HKLM\\Software\\Classes\\your-installer.sumap", "/f"), del.get(0));
        assertEquals(List.of("reg", "delete", "HKLM\\Software\\Classes\\.sumap", "/ve", "/f"), del.get(1));
    }

    @Test
    void systemWideUsesHklm() {
        assertTrue(WindowsAssociationScript.addCommands(spec(), true).get(0).get(2).startsWith("HKLM\\"));
    }
}
