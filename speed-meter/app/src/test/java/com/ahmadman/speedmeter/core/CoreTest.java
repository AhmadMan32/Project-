package com.ahmadman.speedmeter.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public class CoreTest {

    private static final Locale EN = Locale.ROOT;
    private static final Locale TR = new Locale("tr", "TR");

    @Test
    public void bitsLikeTheScreenshot() {
        assertEquals("4.7 kbps", SpeedFormat.format(4700 / 8.0, true, EN));
        assertEquals("9.5 kbps", SpeedFormat.format(9500 / 8.0, true, EN));
        assertEquals("859 kbps", SpeedFormat.format(859_000 / 8.0, true, EN));
        assertEquals("2.3 Mbps", SpeedFormat.format(2_300_000 / 8.0, true, EN));
        assertEquals("0.0 kbps", SpeedFormat.format(0, true, EN));
        assertEquals("1.2 Gbps", SpeedFormat.format(1_200_000_000 / 8.0, true, EN));
    }

    @Test
    public void roundingNeverShows1000() {
        assertEquals("1.0 Mbps", SpeedFormat.format(999_700 / 8.0, true, EN));  // not "1000 kbps"
        assertEquals("10 kbps", SpeedFormat.format(9_960 / 8.0, true, EN));     // not "10.0"
        assertEquals("1.0 MB/s", SpeedFormat.format(1023.8 * 1024, false, EN));
    }

    @Test
    public void bytesAndLocale() {
        assertEquals("590 KB/s", SpeedFormat.format(590 * 1024, false, EN));
        assertEquals("1.5 MB/s", SpeedFormat.format(1.5 * 1024 * 1024, false, EN));
        assertEquals("2,3 Mbps", SpeedFormat.format(2_300_000 / 8.0, true, TR)); // Turkish decimal comma
        assertArrayEquals(new String[]{"2.3", "Mbps"}, SpeedFormat.parts(2_300_000 / 8.0, true, EN));
    }

    @Test
    public void sizes() {
        assertEquals("512 B", SpeedFormat.size(512, EN));
        assertEquals("830 KB", SpeedFormat.size(830 * 1024, EN));
        assertEquals("1.2 GB", SpeedFormat.size((long) (1.2 * 1024 * 1024 * 1024), EN));
    }

    @Test
    public void barLevelIsLogarithmic() {
        assertEquals(0f, SpeedFormat.level(0), 0);
        assertEquals(0f, SpeedFormat.level(100 / 8.0), 0);
        assertEquals(0.4f, SpeedFormat.level(100_000 / 8.0), 0.001);   // 100 kbps
        assertEquals(0.8f, SpeedFormat.level(10_000_000 / 8.0), 0.001); // 10 Mbps
        assertEquals(1f, SpeedFormat.level(1e9), 0);
    }

    @Test
    public void samplerComputesRatesTotalsAndPeaks() {
        SpeedSampler s = new SpeedSampler();
        s.sample(1000, 500, 10_000);
        assertEquals(0, s.download(), 0);              // first sample = baseline
        s.sample(1000 + 300_000, 500 + 20_000, 11_000);
        assertEquals(300_000, s.download(), 0.01);
        assertEquals(20_000, s.upload(), 0.01);
        s.sample(1000 + 400_000, 500 + 20_000, 11_500); // half a second
        assertEquals(200_000, s.download(), 0.01);
        assertEquals(0, s.upload(), 0.01);
        assertEquals(400_000, s.totalDownload());
        assertEquals(20_000, s.totalUpload());
        assertEquals(300_000, s.peakDownload(), 0.01);
    }

    @Test
    public void samplerHandlesResetsGapsAndUnsupported() {
        SpeedSampler s = new SpeedSampler();
        s.sample(5_000_000, 1_000_000, 0);
        s.sample(10, 10, 1000);              // counters reset (network switch): no negative speed
        assertEquals(0, s.download(), 0);
        s.sample(110, 60, 2000);
        assertEquals(100, s.download(), 0.01);
        s.sample(10_000_110, 60, 60_000);    // screen was off for a minute: not averaged
        assertEquals(0, s.download(), 0);
        s.sample(10_000_210, 60, 61_000);
        assertEquals(100, s.download(), 0.01);
        assertEquals(200, s.totalDownload());
        s.sample(-1, -1, 62_000);
        assertFalse(s.isSupported());
        s.restart();
        s.sample(1, 1, 63_000);
        assertTrue(s.isSupported());
        assertEquals(0, s.download(), 0);
    }
}
