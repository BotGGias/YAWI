package de.yawi.installer.core.manifest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code <wizard>} block: page order, the optional license text and the
 * window size.
 *
 * @param pages   page names in flow order
 * @param license {@code classpath:} or file reference of the license text, may be null
 * @param width   preferred window width in pixels ({@code wizard/@width}, default {@link #DEFAULT_WIDTH})
 * @param height  preferred window height in pixels ({@code wizard/@height}, default {@link #DEFAULT_HEIGHT})
 */
public record WizardConfig(List<String> pages, String license, int width, int height) {

    public static final int DEFAULT_WIDTH = 800;
    public static final int DEFAULT_HEIGHT = 520;

    /**
     * Page names the wizard understands. The manifest validator rejects any
     * other name; E04-S02-T02 binds these names to the actual page classes.
     */
    public static final Set<String> KNOWN_PAGES = Set.of(
            "welcome", "license", "components", "destination",
            "source", "summary", "progress", "finish");

    /**
     * The page the wizard puts in front of the first manifest page by itself
     * shown when the product is already registered, skipped
     * otherwise. Reserved, so a manifest cannot list it.
     */
    public static final String MAINTENANCE_PAGE = "maintenance";

    /**
     * The page the wizard puts right after the maintenance page:
     * shown only when the user chose to uninstall, skipped otherwise.
     * Reserved like {@link #MAINTENANCE_PAGE}.
     */
    public static final String UNINSTALL_PAGE = "uninstall";

    /** The wizard's own pages, never listed in a manifest. */
    public static final Set<String> IMPLICIT_PAGES = Set.of(MAINTENANCE_PAGE, UNINSTALL_PAGE);

    public WizardConfig {
        pages = List.copyOf(pages);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("window size must be positive: " + width + "x" + height);
        }
    }

    /** Pages, license and default size; for callers that do not care about the window. */
    public WizardConfig(List<String> pages, String license) {
        this(pages, license, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    public Optional<String> licenseOrEmpty() {
        return Optional.ofNullable(license);
    }
}
