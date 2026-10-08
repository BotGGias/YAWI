package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure path logic, testable on any OS: on Linux {@code Path.of("C:\\x")} is a
 * one-element path whose {@code toString()} returns the string unchanged.
 */
class WindowsPlatformTest {

    private static WindowsPlatform platform(Map<String, String> env) {
        // Windows environment variables are case-insensitive.
        TreeMap<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        map.putAll(env);
        return new WindowsPlatform(Architecture.X64, Environment.of(map,
                Map.of("user.home", "C:\\Users\\fallback", "java.io.tmpdir", "C:\\Temp")));
    }

    private static final WindowsPlatform FULL = platform(Map.of(
            "USERPROFILE", "C:\\Users\\me",
            "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local",
            "APPDATA", "C:\\Users\\me\\AppData\\Roaming",
            "ProgramFiles", "C:\\Program Files",
            "ProgramData", "C:\\ProgramData"));

    private static void assertPath(String expected, Path actual) {
        assertEquals(expected, actual.toString());
    }

    @Test
    void builtInLocations() {
        assertPath("C:\\Users\\me\\AppData\\Local\\your-product", FULL.defaultUserInstallDir("your-product"));
        assertPath("C:\\Program Files\\your-product", FULL.defaultSystemInstallDir("your-product"));
        assertPath("C:\\Users\\me", FULL.homeDir());
        assertPath("C:\\Users\\me\\Desktop", FULL.desktopDir());
        assertPath("C:\\Users\\me\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs",
                FULL.applicationMenuDir(false));
        assertPath("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs", FULL.applicationMenuDir(true));
        assertPath("C:\\Users\\me\\AppData\\Roaming\\your-product", FULL.configDir("your-product"));
        assertPath("C:\\Temp", FULL.tempDir());
        assertPath("C:\\Users\\me\\AppData\\Local\\your-product\\logs", FULL.logDir("your-product"));
    }

    @Test
    void fallsBackWhenVariablesAreMissing() {
        WindowsPlatform bare = platform(Map.of());
        assertPath("C:\\Users\\fallback\\AppData\\Local\\x", bare.defaultUserInstallDir("x"));
        assertPath("C:\\Program Files\\x", bare.defaultSystemInstallDir("x"));
        assertPath("C:\\Users\\fallback\\AppData\\Roaming\\x", bare.configDir("x"));
        assertPath("C:\\ProgramData\\Microsoft\\Windows\\Start Menu\\Programs", bare.applicationMenuDir(true));
    }

    @Test
    void expandsPercentVariablesCaseInsensitively() {
        assertEquals("C:\\Users\\me\\AppData\\Local\\your-installer",
                FULL.expandVariables("%LOCALAPPDATA%\\your-installer"));
        assertEquals("C:\\Program Files\\your-installer", FULL.expandVariables("%ProgramFiles%\\your-installer"));
        assertEquals("C:\\Program Files\\x", FULL.expandVariables("%PROGRAMFILES%\\x"));
        assertEquals("C:\\Users\\me and C:\\Users\\me", FULL.expandVariables("%USERPROFILE% and %userprofile%"));
    }

    @Test
    void leavesUnknownVariablesAndPosixSyntaxAlone() {
        assertEquals("%NOPE%\\x", FULL.expandVariables("%NOPE%\\x"));
        assertEquals("$HOME\\x", FULL.expandVariables("$HOME\\x"));
        assertEquals("50%", FULL.expandVariables("50%"));
    }

    @Test
    void manifestDefaultWinsOverBuiltIn() {
        // literally the values from installer.xml
        assertPath("C:\\Users\\me\\AppData\\Local\\your-installer",
                FULL.installDir("%LOCALAPPDATA%\\your-installer", "your-product", false));
        assertPath("C:\\Program Files\\your-installer",
                FULL.installDir("%ProgramFiles%\\your-installer", "your-product", true));
        assertPath("C:\\Users\\me\\AppData\\Local\\your-product", FULL.installDir(null, "your-product", false));
        assertPath("C:\\Program Files\\your-product", FULL.installDir("  ", "your-product", true));
    }
}
