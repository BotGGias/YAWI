package de.yawi.installer.core.manifest;

import de.yawi.installer.core.platform.OperatingSystem;

import java.util.Map;
import java.util.Optional;

/**
 * The {@code <destination>} block.
 *
 * <p>Paths still contain platform placeholders such as {@code %LOCALAPPDATA%}
 * or {@code $HOME}; the platform abstraction resolves them.
 *
 * @param minFreeBytes required free space, {@code -1} if not given
 * @param defaults     user-space default per OS
 * @param systemWide   system-wide default per OS, used only if the user opts in (E12)
 */
public record DestinationConfig(boolean allowUserChange, long minFreeBytes,
                                Map<OperatingSystem, String> defaults,
                                Map<OperatingSystem, String> systemWide) {

    public DestinationConfig {
        defaults = Map.copyOf(defaults);
        systemWide = Map.copyOf(systemWide);
    }

    public Optional<String> defaultFor(OperatingSystem os) {
        return Optional.ofNullable(defaults.get(os));
    }

    public Optional<String> systemWideFor(OperatingSystem os) {
        return Optional.ofNullable(systemWide.get(os));
    }
}
