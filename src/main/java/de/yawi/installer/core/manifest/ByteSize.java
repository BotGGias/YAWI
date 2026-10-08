package de.yawi.installer.core.manifest;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses size attributes such as {@code 700MiB} or {@code 2GiB}.
 *
 * <p>Decimal units ({@code KB}, {@code MB}, …) are powers of 1000, binary units
 * ({@code KiB}, {@code MiB}, …) powers of 1024; a bare number is bytes. The
 * schema checks the form with {@link #PATTERN}; a shared test keeps the two in
 * step.
 */
public final class ByteSize {

    /** Exactly the pattern used by the {@code byteSize} type in the XSD. */
    public static final String PATTERN = "[0-9]+([KMGT]i?B|B)?";

    private static final Pattern REGEX = Pattern.compile("([0-9]+)([KMGT]i?B|B)?");

    private static final Map<String, Long> MULTIPLIERS = Map.ofEntries(
            Map.entry("B", 1L),
            Map.entry("KB", 1_000L),
            Map.entry("MB", 1_000_000L),
            Map.entry("GB", 1_000_000_000L),
            Map.entry("TB", 1_000_000_000_000L),
            Map.entry("KiB", 1L << 10),
            Map.entry("MiB", 1L << 20),
            Map.entry("GiB", 1L << 30),
            Map.entry("TiB", 1L << 40));

    private static final String[] BINARY_UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private ByteSize() {
    }

    /**
     * Human readable binary size for the UI, e.g. {@code 700 MiB} or
     * {@code 1.5 GiB}; whole bytes below 1 KiB. Uses the locale's decimal
     * separator.
     */
    public static String format(long bytes, Locale locale) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative size: " + bytes);
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < BINARY_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        if (unit == 0) {
            return bytes + " B";
        }
        String pattern = value >= 100 || value == Math.rint(value) ? "%.0f %s" : "%.1f %s";
        return String.format(locale == null ? Locale.ROOT : locale, pattern, value, BINARY_UNITS[unit]);
    }

    /**
     * @throws IllegalArgumentException if the text does not match {@link #PATTERN}
     *         or the value overflows a {@code long}
     */
    public static long parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("size is null");
        }
        Matcher matcher = REGEX.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("invalid size '" + text + "', expected e.g. 700MiB or 2GB");
        }
        String unit = matcher.group(2) == null ? "B" : matcher.group(2);
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), MULTIPLIERS.get(unit));
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("size '" + text + "' is too large", e);
        }
    }
}
