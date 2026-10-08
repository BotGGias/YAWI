package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.Environment;
import de.yawi.installer.core.platform.Platform;
import de.yawi.installer.core.platform.PlatformFactory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E13-S01: where a shortcut goes on each platform. */
class ShortcutLayoutTest {

    private static final Platform LINUX = PlatformFactory.detect("Linux", "amd64",
            Environment.of(Map.of("HOME", "/home/me"), Map.of("user.home", "/home/me", "java.io.tmpdir", "/tmp")));
    private static final Platform MAC = PlatformFactory.detect("Mac OS X", "aarch64",
            Environment.of(Map.of("HOME", "/Users/me"), Map.of("user.home", "/Users/me", "java.io.tmpdir", "/tmp")));
    private static final Platform WINDOWS;

    static {
        Map<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.put("USERPROFILE", "C:\\Users\\me");
        env.put("APPDATA", "C:\\Users\\me\\AppData\\Roaming");
        env.put("PROGRAMDATA", "C:\\ProgramData");
        WINDOWS = PlatformFactory.detect("Windows 11", "amd64",
                Environment.of(env, Map.of("user.home", "C:\\Users\\me", "java.io.tmpdir", "C:\\Temp")));
    }

    private static ShortcutSpec spec(String name, Path target, boolean desktop, boolean menu) {
        // Not target.getParent(): a Windows path has no parent on a POSIX test machine.
        return new ShortcutSpec("your-product", "main", name, target, Path.of("work"), Optional.empty(),
                desktop, menu);
    }

    @Test
    void linuxUsesIdsForDesktopFilesAndTheIconThemeNextToTheApplications() {
        ShortcutLayout.Placement p = ShortcutLayout.of(LINUX, false, spec("Any Name", Path.of("/home/me/su/bin/su"), true, true));
        assertEquals("/home/me/.local/share/applications/your-product-main.desktop", p.menuFile().orElseThrow().toString());
        assertEquals("/home/me/Desktop/your-product-main.desktop", p.desktopFile().orElseThrow().toString());
        assertEquals("/home/me/.local/share/icons", p.iconDir().orElseThrow().toString());
        assertEquals("/home/me/.local/share/icons/hicolor/256x256/apps/your-product-main.png",
                ShortcutLayout.iconFile(p.iconDir().get(), spec("x", Path.of("/x"), true, true), 256, 256).toString());

        ShortcutLayout.Placement system = ShortcutLayout.of(LINUX, true, spec("Any", Path.of("/opt/su/su"), false, true));
        assertEquals("/usr/share/applications/your-product-main.desktop", system.menuFile().orElseThrow().toString());
        assertTrue(system.desktopFile().isEmpty(), "desktop=false");
        assertEquals("/usr/share/icons", system.iconDir().orElseThrow().toString());
    }

    @Test
    void windowsNamesTheLnkAfterTheVisibleName() {
        // Parent and name checked apart: Path.resolve uses this machine's separator, not Windows'.
        ShortcutLayout.Placement p = ShortcutLayout.of(WINDOWS, false,
                spec("your-installer?", Path.of("C:\\Games\\su\\yawi.exe"), true, true));
        assertEquals(WINDOWS.applicationMenuDir(false), p.menuFile().orElseThrow().getParent());
        assertEquals("YOUR INSTALLER.lnk", p.menuFile().orElseThrow().getFileName().toString());
        assertEquals(WINDOWS.desktopDir(), p.desktopFile().orElseThrow().getParent());
        assertEquals("YOUR INSTALLER.lnk", p.desktopFile().orElseThrow().getFileName().toString());
        assertTrue(p.iconDir().isEmpty());
        assertEquals(WINDOWS.applicationMenuDir(true),
                ShortcutLayout.of(WINDOWS, true, spec("YOUR INSTALLER", Path.of("C:\\su\\yawi.exe"), false, true))
                        .menuFile().orElseThrow().getParent());
    }

    @Test
    void macLinksTheAppBundleWhenTheTargetLiesInOne() {
        ShortcutLayout.Placement app = ShortcutLayout.of(MAC, false,
                spec("YOUR INSTALLER", Path.of("/Users/me/Applications/su/YOUR INSTALLER.app/Contents/MacOS/su"), true, true));
        assertEquals("/Users/me/Applications/YOUR INSTALLER.app", app.menuFile().orElseThrow().toString());
        assertEquals("/Users/me/Desktop/YOUR INSTALLER.app", app.desktopFile().orElseThrow().toString());
        assertEquals(Path.of("/Users/me/Applications/su/YOUR INSTALLER.app"),
                ShortcutLayout.appBundleOf(Path.of("/Users/me/Applications/su/YOUR INSTALLER.app/Contents/MacOS/su")).orElseThrow());

        ShortcutLayout.Placement plain = ShortcutLayout.of(MAC, true, spec("su", Path.of("/Applications/su/bin/su"), false, true));
        assertEquals("/Applications/su", plain.menuFile().orElseThrow().toString());
        assertTrue(ShortcutLayout.appBundleOf(Path.of("/Applications/su/bin/su")).isEmpty());
    }

    @Test
    void namesLoseForbiddenCharactersAndFallBackToTheId() {
        assertEquals("YOUR INSTALLER", ShortcutLayout.sanitize("your-installer?", "id"));
        assertEquals("ab", ShortcutLayout.sanitize(" a\\/b... ", "id"));
        assertEquals("id", ShortcutLayout.sanitize("<>|", "id"));
    }
}
