package de.yawi.installer.core.platform;

import java.util.Locale;
import java.util.Optional;

/** CPU architectures the installer distinguishes; {@code ${arch}} in the manifest. */
public enum Architecture {
    X64("x64"),
    AARCH64("aarch64"),
    UNKNOWN(null);

    private final String manifestName;

    Architecture(String manifestName) {
        this.manifestName = manifestName;
    }

    /** The value of the {@code ${arch}} placeholder; empty for {@link #UNKNOWN}. */
    public Optional<String> manifestName() {
        return Optional.ofNullable(manifestName);
    }

    /** Detects the architecture from {@code os.arch} ({@code amd64}, {@code x86_64}, {@code arm64}, …). */
    public static Architecture fromOsArch(String osArch) {
        if (osArch == null) {
            return UNKNOWN;
        }
        return switch (osArch.toLowerCase(Locale.ROOT).trim()) {
            case "amd64", "x86_64", "x86-64", "x64" -> X64;
            case "aarch64", "arm64" -> AARCH64;
            default -> UNKNOWN;
        };
    }
}
