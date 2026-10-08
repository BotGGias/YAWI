package de.yawi.installer.core.download;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeedMeterTest {

    @Test
    void averagesOverTheWindowAndForgetsOlderSamples() {
        AtomicLong clock = new AtomicLong();
        SpeedMeter meter = new SpeedMeter(clock::get);
        assertEquals(0.0, meter.bytesPerSecond());
        assertEquals(-1, meter.etaSeconds(100));

        meter.update(0);
        clock.set(1_000_000_000L);
        meter.update(1000);              // 1000 B/s over the first second
        assertEquals(1000.0, meter.bytesPerSecond(), 1e-6);
        assertEquals(5, meter.etaSeconds(5000));

        clock.set(2_000_000_000L);
        meter.update(3000);              // 2000 B/s in the second second -> 1500 average
        assertEquals(1500.0, meter.bytesPerSecond(), 1e-6);

        clock.set(8_000_000_000L);
        meter.update(3000);              // stalled: the window drops the early fast samples
        assertEquals(0.0, meter.bytesPerSecond(), 1e-6);
        assertEquals(-1, meter.etaSeconds(100));
    }
}
