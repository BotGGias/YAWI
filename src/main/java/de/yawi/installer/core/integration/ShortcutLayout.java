package de.yawi.installer.core.integration;

import de.yawi.installer.core.platform.OperatingSystem;
import de.yawi.installer.core.platform.Platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the files of a shortcut go on this platform:
 *
 * <ul>
 *   <li><b>Linux:</b> a {@code <productId>-<shortcutId>.desktop} file in the
 *       applications directory (menu) and on the desktop; the icon as a PNG
 *       in the {@code hicolor} theme next to the applications directory
 *       ({@code ~/.local/share/icons} or {@code /usr/share/icons}).</li>
 *   <li><b>Windows:</b> {@code <name>.lnk} in the Start Menu's Programs folder
 *       and on the desktop.</li>
 *   <li><b>macOS:</b> a symbolic link named after the shortcut in the
 *       Applications folder and on the desktop; if the target lies inside an
 *       {@code .app} bundle, the bundle is linked as {@code <name>.app}.</li>
 * </ul>
 *
 * <p>Names on Windows and macOS are the visible name with the characters a
 * file name cannot hold removed; Linux uses the manifest ids, which are safe.
 */
public final class ShortcutLayout {

    /**
     * The files a shortcut consists of. {@code iconDir} is the icon theme root
     * (Linux only); the file below it depends on the icon's pixel size, see
     * {@link #iconFile}.
     */
    public record Placement(Optional<Path> menuFile, Optional<Path> desktopFile, Optional<Path> iconDir) {
    }

    private ShortcutLayout() {
    }

    public static Placement of(Platform platform, boolean systemWide, ShortcutSpec spec) {
        Path menuDir = platform.applicationMenuDir(systemWide);
        Path desktopDir = platform.desktopDir();
        String file = switch (platform.os()) {
            case LINUX -> baseName(spec) + ".desktop";
            case WINDOWS -> sanitize(spec.name(), spec.shortcutId()) + ".lnk";
            case MACOS -> sanitize(spec.name(), spec.shortcutId())
                    + (appBundleOf(spec.target()).isPresent() ? ".app" : "");
            case UNKNOWN -> throw new IllegalStateException("unsupported platform " + platform.os());
        };
        return new Placement(
                spec.menu() ? Optional.of(menuDir.resolve(file)) : Optional.empty(),
                spec.desktop() ? Optional.of(desktopDir.resolve(file)) : Optional.empty(),
                platform.os() == OperatingSystem.LINUX ? Optional.of(menuDir.resolveSibling("icons")) : Optional.empty());
    }

    /** {@code <productId>-<shortcutId>}: the desktop file's and the icon's name on Linux. */
    public static String baseName(ShortcutSpec spec) {
        return spec.productId() + "-" + spec.shortcutId();
    }

    /** The icon file for a PNG of the given size in the {@code hicolor} theme below {@code iconDir}. */
    public static Path iconFile(Path iconDir, ShortcutSpec spec, int width, int height) {
        return iconDir.resolve("hicolor").resolve(width + "x" + height).resolve("apps")
                .resolve(baseName(spec) + ".png");
    }

    /** The {@code .app} bundle a macOS target lies in, if any. */
    public static Optional<Path> appBundleOf(Path target) {
        for (Path p = target; p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().endsWith(".app")) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** The name without the characters no file system accepts, or {@code fallback} if nothing is left. */
    static String sanitize(String name, String fallback) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
