package com.ahmadman.speedmeter.core;

import java.util.Locale;

/** Human readable speeds: "4.7 kbps", "2.3 Mbps" (bits) or "590 KB/s", "1.2 MB/s" (bytes). */
public final class SpeedFormat {

    private static final String[] BIT_UNITS = {"kbps", "Mbps", "Gbps"};
    private static final String[] BYTE_UNITS = {"KB/s", "MB/s", "GB/s"};
    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    private SpeedFormat() {
    }

    /** {number, unit}; bits use 1000 steps, bytes 1024 (like phones and browsers do). */
    public static String[] parts(double bytesPerSecond, boolean bits, Locale locale) {
        double v = Math.max(0, bytesPerSecond);
        String[] units;
        double step;
        if (bits) {
            v = v * 8 / 1000.0; // kbps
            units = BIT_UNITS;
            step = 1000;
        } else {
            v = v / 1024.0; // KB/s
            units = BYTE_UNITS;
            step = 1024;
        }
        int u = 0;
        while (u < units.length - 1 && round(v) >= 1000) {
            v /= step;
            u++;
        }
        return new String[]{number(v, locale), units[u]};
    }

    public static String format(double bytesPerSecond, boolean bits, Locale locale) {
        String[] p = parts(bytesPerSecond, bits, locale);
        return p[0] + " " + p[1];
    }

    /** One decimal below 10, whole numbers above ("4.7", "86", "859"). */
    static String number(double v, Locale locale) {
        if (v < 9.95) {
            return String.format(locale, "%.1f", v);
        }
        return String.format(locale, "%.0f", v);
    }

    private static double round(double v) {
        return v < 9.95 ? Math.round(v * 10) / 10.0 : Math.round(v);
    }

    /** Data amount: "830 KB", "1.2 GB". */
    public static String size(long bytes, Locale locale) {
        double v = Math.max(0, bytes);
        int u = 0;
        while (u < SIZE_UNITS.length - 1 && v >= 1000) {
            v /= 1024;
            u++;
        }
        String n = u == 0 ? String.format(locale, "%.0f", v)
                : v < 9.95 ? String.format(locale, "%.1f", v) : String.format(locale, "%.0f", v);
        return n + " " + SIZE_UNITS[u];
    }

    /**
     * Bar length 0..1 on a log scale from 1 kbit/s to 100 Mbit/s, so both slow and fast
     * connections show visible movement.
     */
    public static float level(double bytesPerSecond) {
        double bitsPerSecond = bytesPerSecond * 8;
        if (bitsPerSecond < 1000) {
            return 0f;
        }
        double f = (Math.log10(bitsPerSecond) - 3) / (8 - 3);
        return (float) Math.max(0, Math.min(1, f));
    }
}
