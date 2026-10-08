package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinuxPlatformTest {

    private static LinuxPlatform platform(Map<String, String> env) {
        return new LinuxPlatform(Architecture.X64, Environment.of(env,
                Map.of("user.home", "/home/fallback", "java.io.tmpdir", "/tmp")));
    }

    private static final LinuxPlatform HOME_ONLY = platform(Map.of("HOME", "/home/me"));
    private static final LinuxPlatform XDG = platform(Map.of(
            "HOME", "/home/me",
            "XDG_DATA_HOME", "/data/me",
            "XDG_CONFIG_HOME", "/cfg/me",
            "XDG_STATE_HOME", "/state/me"));

    private static void assertPath(String expected, Path actual) {
        assertEquals(expected, actual.toString());
    }

    @Test
    void builtInLocationsFollowXdgDefaults() {
        assertPath("/home/me/.local/share/your-product", HOME_ONLY.defaultUserInstallDir("your-product"));
        assertPath("/opt/your-product", HOME_ONLY.defaultSystemInstallDir("your-product"));
        assertPath("/home/me", HOME_ONLY.homeDir());
        assertPath("/home/me/Desktop", HOME_ONLY.desktopDir());
        assertPath("/home/me/.local/share/applications", HOME_ONLY.applicationMenuDir(false));
        assertPath("/usr/share/applications", HOME_ONLY.applicationMenuDir(true));
        assertPath("/home/me/.config/your-product", HOME_ONLY.configDir("your-product"));
        assertPath("/tmp", HOME_ONLY.tempDir());
        assertPath("/home/me/.local/state/your-product", HOME_ONLY.logDir("your-product"));
    }

    @Test
    void xdgVariablesOverrideTheDefaults() {
        assertPath("/data/me/x", XDG.defaultUserInstallDir("x"));
        assertPath("/data/me/applications", XDG.applicationMenuDir(false));
        assertPath("/cfg/me/x", XDG.configDir("x"));
        assertPath("/state/me/x", XDG.logDir("x"));
    }

    @Test
    void homeFallsBackToUserHomeProperty() {
        assertPath("/home/fallback/.local/share/x", platform(Map.of()).defaultUserInstallDir("x"));
        assertPath("/home/fallback", platform(Map.of("HOME", "  ")).homeDir());
    }

    @Test
    void expandsDollarVariablesAndTilde() {
        assertEquals("/home/me/.local/share/your-product",
                HOME_ONLY.expandVariables("$HOME/.local/share/your-product"));
        assertEquals("/data/me/x", XDG.expandVariables("${XDG_DATA_HOME}/x"));
        assertEquals("/home/me/Applications", HOME_ONLY.expandVariables("~/Applications"));
        assertEquals("/home/me", HOME_ONLY.expandVariables("~"));
        assertEquals("/home/me/a/home/me/b", HOME_ONLY.expandVariables("$HOME/a${HOME}/b"));
    }

    @Test
    void leavesUnknownVariablesAndWindowsSyntaxAlone() {
        assertEquals("$NOPE/x", HOME_ONLY.expandVariables("$NOPE/x"));
        assertEquals("${NOPE}/x", HOME_ONLY.expandVariables("${NOPE}/x"));
        assertEquals("%LOCALAPPDATA%/x", HOME_ONLY.expandVariables("%LOCALAPPDATA%/x"));
        assertEquals("a~/b", HOME_ONLY.expandVariables("a~/b"));
        assertEquals("$", HOME_ONLY.expandVariables("$"));
    }

    @Test
    void manifestDefaultWinsOverBuiltIn() {
        assertPath("/home/me/.local/share/your-product",
                HOME_ONLY.installDir("$HOME/.local/share/your-product", "your-product", false));
        assertPath("/opt/your-product", HOME_ONLY.installDir("/opt/your-product", "your-product", true));
        assertPath("/home/me/.local/share/su", HOME_ONLY.installDir(null, "su", false));
        assertPath("/opt/su", HOME_ONLY.installDir(null, "su", true));
    }
}
