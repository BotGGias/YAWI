package de.yawi.installer.core.download;

import de.yawi.installer.core.manifest.ByteSize;

import java.util.Locale;

/** Human readable speed and time for the progress page (E07-S04-T03). */
public final class Formats {

    private Formats() {
    }

    /** {@code 2.1 MiB/s}. */
    public static String speed(double bytesPerSecond, Locale locale) {
        return ByteSize.format(Math.max(0, Math.round(bytesPerSecond)), locale) + "/s";
    }

    /** {@code 0:42}, {@code 5:07}, {@code 1:02:03}; {@code -} if unknown. */
    public static String duration(long seconds) {
        if (seconds < 0) {
            return "-";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
