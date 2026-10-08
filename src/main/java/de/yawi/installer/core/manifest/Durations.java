package de.yawi.installer.core.manifest;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manifest durations such as {@code 90s}, {@code 2m} or {@code 500ms} (E08). */
public final class Durations {

    /** Same pattern as the XSD's {@code duration} type. */
    public static final Pattern PATTERN = Pattern.compile("([0-9]+)(ms|s|m)");

    private Durations() {
    }

    /** @throws IllegalArgumentException if {@code text} does not match {@link #PATTERN} */
    public static Duration parse(String text) {
        Matcher m = PATTERN.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a duration: '" + text + "' (expected e.g. 90s, 2m, 500ms)");
        }
        long value = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(value);
            case "s" -> Duration.ofSeconds(value);
            default -> Duration.ofMinutes(value);
        };
    }

    /** {@code 90s}, {@code 2m}, {@code 500ms} - the shortest exact form. */
    public static String format(Duration duration) {
        long ms = duration.toMillis();
        if (ms % 60_000 == 0) {
            return (ms / 60_000) + "m";
        }
        if (ms % 1000 == 0) {
            return (ms / 1000) + "s";
        }
        return ms + "ms";
    }
}
