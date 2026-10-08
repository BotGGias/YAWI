package de.yawi.installer.core.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code reg} command lines and value maths that add directories to the
 * Windows PATH. The user PATH lives in {@code HKCU\Environment} (no
 * admin), the system PATH in the Session Manager key under {@code HKLM}
 * (elevated). {@code reg add} does not broadcast the change, so it takes effect
 * in new sessions only - which matches the acceptance criteria. The value is
 * kept below {@link #MAX_PATH_LENGTH}; a longer result is refused rather than
 * silently truncated.
 */
public final class WindowsPathRegistry {

    /** Practical safe upper bound for the PATH value; beyond it the append is refused. */
    public static final int MAX_PATH_LENGTH = 2048;

    private WindowsPathRegistry() {
    }

    /** The registry key that holds the PATH for this scope. */
    public static String environmentKey(boolean systemWide) {
        return systemWide
                ? "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"
                : "HKCU\\Environment";
    }

    /** {@code reg query} that reads the current PATH value. */
    public static List<String> queryCommand(boolean systemWide) {
        return List.of("reg", "query", environmentKey(systemWide), "/v", "Path");
    }

    /** {@code reg add} that writes {@code value} back as the PATH. */
    public static List<String> addCommand(String value, boolean systemWide) {
        return List.of("reg", "add", environmentKey(systemWide), "/v", "Path", "/t", "REG_EXPAND_SZ",
                "/d", value, "/f");
    }

    /** The PATH value out of {@code reg query} output; empty if the value is not set. */
    public static Optional<String> parseValue(String queryOutput) {
        for (String line : queryOutput.split("\\R")) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase("Path")) {
                for (String type : List.of("REG_EXPAND_SZ", "REG_SZ", "REG_NONE")) {
                    int idx = parts[1].indexOf(type);
                    if (idx >= 0) {
                        return Optional.of(parts[1].substring(idx + type.length()).strip());
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The directories not already in {@code current} (case-insensitive), in order (dedup). */
    public static List<String> missing(String current, List<String> dirs) {
        List<String> have = new ArrayList<>();
        for (String part : current.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                have.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        List<String> result = new ArrayList<>();
        for (String dir : dirs) {
            if (!have.contains(dir.strip().toLowerCase(Locale.ROOT)) && !result.contains(dir)) {
                result.add(dir);
            }
        }
        return result;
    }

    /**
     * The new PATH value after appending the missing directories, or empty when
     * there is nothing to add.
     */
    public static Optional<String> appended(String current, List<String> dirs) {
        List<String> add = missing(current, dirs);
        if (add.isEmpty()) {
            return Optional.empty();
        }
        String joined = String.join(";", add);
        return Optional.of(current.isEmpty() ? joined : trimTrailingSeparator(current) + ";" + joined);
    }

    /**
     * The new PATH value after removing the given directories (case-insensitive),
     * or empty when none of them was present.
     */
    public static Optional<String> removed(String current, List<String> dirs) {
        List<String> remove = new ArrayList<>();
        for (String dir : dirs) {
            remove.add(dir.strip().toLowerCase(Locale.ROOT));
        }
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String part : current.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty() && remove.contains(trimmed.toLowerCase(Locale.ROOT))) {
                changed = true;
                continue;
            }
            if (!part.isEmpty()) {
                kept.add(part);
            }
        }
        return changed ? Optional.of(String.join(";", kept)) : Optional.empty();
    }

    private static String trimTrailingSeparator(String value) {
        String result = value;
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
