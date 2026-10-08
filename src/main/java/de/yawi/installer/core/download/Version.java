package de.yawi.installer.core.download;

import java.util.regex.Pattern;

/**
 * Compares version strings segment by segment: numeric segments by value
 * ({@code 0.9.5 < 0.10.0}), others lexicographically; a missing segment counts
 * as zero ({@code 1.2 == 1.2.0}). Good enough for "is there something newer?".
 */
public final class Version {

    private static final Pattern SEPARATOR = Pattern.compile("[.\\-+_]");

    private Version() {
    }

    public static int compare(String a, String b) {
        String[] as = SEPARATOR.split(a.trim());
        String[] bs = SEPARATOR.split(b.trim());
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            String x = i < as.length ? as[i] : "0";
            String y = i < bs.length ? bs[i] : "0";
            int c = compareSegment(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static int compareSegment(String x, String y) {
        boolean nx = x.chars().allMatch(Character::isDigit) && !x.isEmpty();
        boolean ny = y.chars().allMatch(Character::isDigit) && !y.isEmpty();
        if (nx && ny) {
            return Long.compare(Long.parseLong(x), Long.parseLong(y));
        }
        if (nx != ny) {
            // "1.0" beats "1.0-beta": a number outranks a word in the same place.
            return nx ? 1 : -1;
        }
        return x.compareToIgnoreCase(y);
    }
}
