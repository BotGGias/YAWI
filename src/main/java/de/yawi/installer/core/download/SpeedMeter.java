package de.yawi.installer.core.download;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Transfer speed as a moving average over the last few seconds, so a burst
 * or a stall does not swing the estimate wildly.
 */
final class SpeedMeter {

    static final long WINDOW_NANOS = 5_000_000_000L;

    private record Sample(long nanos, long bytes) {
    }

    private final LongSupplier clock;
    private final Deque<Sample> samples = new ArrayDeque<>();

    SpeedMeter() {
        this(System::nanoTime);
    }

    SpeedMeter(LongSupplier nanoClock) {
        this.clock = nanoClock;
    }

    /** Records the cumulative byte count. */
    void update(long cumulativeBytes) {
        long now = clock.getAsLong();
        samples.addLast(new Sample(now, cumulativeBytes));
        while (samples.size() > 2 && now - samples.peekFirst().nanos() > WINDOW_NANOS) {
            samples.removeFirst();
        }
    }

    /** Bytes per second over the window; 0 until two samples exist. */
    double bytesPerSecond() {
        if (samples.size() < 2) {
            return 0;
        }
        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        long nanos = last.nanos() - first.nanos();
        if (nanos <= 0) {
            return 0;
        }
        return (last.bytes() - first.bytes()) * 1e9 / nanos;
    }

    /** Seconds left for {@code remaining} bytes at the current speed, {@code -1} if unknown. */
    long etaSeconds(long remaining) {
        double speed = bytesPerSecond();
        if (remaining < 0 || speed <= 0) {
            return -1;
        }
        return Math.round(remaining / speed);
    }
}
