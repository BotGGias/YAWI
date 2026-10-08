package de.yawi.installer.core.error;

import java.time.Duration;
import java.util.List;

/**
 * A process the installer had to wait for ({@code --wait-pid}) was still
 * running when the grace period ended (update contract). Nothing has
 * been written at that point.
 */
public class WaitTimeoutException extends InstallerException {

    private final List<Long> stillRunning;

    public WaitTimeoutException(List<Long> stillRunning, Duration waited) {
        super(ErrorCode.WAIT_TIMEOUT, ErrorCode.WAIT_TIMEOUT.messageKey(),
                new Object[] {String.valueOf(waited.toSeconds()), join(stillRunning)},
                "process(es) " + join(stillRunning) + " still running after " + waited.toSeconds() + " s", null, null);
        this.stillRunning = List.copyOf(stillRunning);
    }

    public List<Long> stillRunning() {
        return stillRunning;
    }

    private static String join(List<Long> pids) {
        return pids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }
}
