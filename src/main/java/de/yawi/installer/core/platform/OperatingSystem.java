package de.yawi.installer.core.platform;

import java.util.Locale;
import java.util.Optional;

/**
 * Operating system families the installer distinguishes.
 *
 * <p>Keyed by the manifest's {@code os} attributes (E01) and detected from
 * {@code os.name} by {@link PlatformFactory} (E02).
 */
public enum OperatingSystem {
    WINDOWS("windows"),
    LINUX("linux"),
    MACOS("macos"),
    UNKNOWN(null);

    private final String manifestName;

    OperatingSystem(String manifestName) {
        this.manifestName = manifestName;
    }

    /** The value used in the manifest's {@code os} attributes; empty for {@link #UNKNOWN}. */
    public Optional<String> manifestName() {
        return Optional.ofNullable(manifestName);
    }

    /**
     * Detects the family from the {@code os.name} system property, tolerant of
     * the spellings seen in the wild ({@code Windows 11}, {@code Mac OS X},
     * {@code Darwin}, …).
     */
    public static OperatingSystem fromOsName(String osName) {
        if (osName == null) {
            return UNKNOWN;
        }
        String name = osName.toLowerCase(Locale.ROOT).trim();
        if (name.startsWith("windows")) {
            return WINDOWS;
        }
        if (name.startsWith("linux")) {
            return LINUX;
        }
        if (name.startsWith("mac") || name.startsWith("darwin")) {
            return MACOS;
        }
        return UNKNOWN;
    }

    /** Resolves a manifest {@code os} attribute; unknown names yield {@link #UNKNOWN}. */
    public static OperatingSystem fromManifestName(String name) {
        for (OperatingSystem os : values()) {
            if (os.manifestName != null && os.manifestName.equals(name)) {
                return os;
            }
        }
        return UNKNOWN;
    }
}
