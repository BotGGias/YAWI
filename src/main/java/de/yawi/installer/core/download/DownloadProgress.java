package de.yawi.installer.core.download;

/**
 * A snapshot of a running download.
 *
 * @param bytes          received so far (including a resumed part)
 * @param total          expected size, {@code -1} if the server does not say
 * @param bytesPerSecond current speed, moving average; {@code 0} at the start
 * @param etaSeconds     estimated remaining time, {@code -1} if unknown
 */
public record DownloadProgress(long bytes, long total, double bytesPerSecond, long etaSeconds) {

    /** 0..1, or -1 if the total is unknown. */
    public double fraction() {
        return total <= 0 ? -1 : Math.min(1.0, (double) bytes / total);
    }
}
