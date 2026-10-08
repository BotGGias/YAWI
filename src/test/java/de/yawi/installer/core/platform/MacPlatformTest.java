package de.yawi.installer.core.platform;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacPlatformTest {

    private static final MacPlatform MAC = new MacPlatform(Architecture.AARCH64, Environment.of(
            Map.of("HOME", "/Users/me"),
            Map.of("user.home", "/Users/fallback", "java.io.tmpdir", "/var/folders/xy/T")));

    private static void assertPath(String expected, Path actual) {
        assertEquals(expected, actual.toString());
    }

    @Test
    void builtInLocations() {
        assertPath("/Users/me/Applications/your-product", MAC.defaultUserInstallDir("your-product"));
        assertPath("/Applications/your-product", MAC.defaultSystemInstallDir("your-product"));
        assertPath("/Users/me", MAC.homeDir());
        assertPath("/Users/me/Desktop", MAC.desktopDir());
        assertPath("/Users/me/Applications", MAC.applicationMenuDir(false));
        assertPath("/Applications", MAC.applicationMenuDir(true));
        assertPath("/Users/me/Library/Application Support/your-product", MAC.configDir("your-product"));
        assertPath("/var/folders/xy/T", MAC.tempDir());
        assertPath("/Users/me/Library/Logs/your-product", MAC.logDir("your-product"));
    }

    @Test
    void manifestDefaultsFromInstallerXml() {
        assertPath("/Users/me/Applications/your-installer",
                MAC.installDir("$HOME/Applications/your-installer", "your-product", false));
        assertPath("/Applications/your-installer",
                MAC.installDir("/Applications/your-installer", "your-product", true));
        assertPath("/Users/me/Applications/your-product", MAC.installDir(null, "your-product", false));
    }

    @Test
    void expandsTilde() {
        assertEquals("/Users/me/Applications/X", MAC.expandVariables("~/Applications/X"));
    }
}
