package de.yawi.installer.core.platform;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Platforms for tests that really install: this operating system, but with
 * {@code home} as the user's home (and profile, application data, desktop),
 * so shortcuts, registers and the like land in a scratch directory
 * and never in the developer's menu.
 */
public final class TestPlatforms {

    private TestPlatforms() {
    }

    /** The current OS and architecture with every user location below {@code home}. */
    public static Platform scratch(Path home) {
        Path absolute = home.toAbsolutePath().normalize();
        Map<String, String> env = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        env.put("HOME", absolute.toString());
        env.put("USERPROFILE", absolute.toString());
        env.put("APPDATA", absolute.resolve("AppData").resolve("Roaming").toString());
        env.put("LOCALAPPDATA", absolute.resolve("AppData").resolve("Local").toString());
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        Map<String, String> properties = new HashMap<>();
        properties.put("user.home", absolute.toString());
        properties.put("java.io.tmpdir", System.getProperty("java.io.tmpdir"));
        properties.put("user.name", System.getProperty("user.name"));
        return PlatformFactory.detect(System.getProperty("os.name"), System.getProperty("os.arch"),
                Environment.of(env, properties));
    }
}
