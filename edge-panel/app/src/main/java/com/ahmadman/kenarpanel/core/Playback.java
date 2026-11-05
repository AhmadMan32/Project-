package com.ahmadman.kenarpanel.core;

import java.util.Locale;

/** Where a song is right now, from the player's last reported position, and how to show it. */
public final class Playback {

    private Playback() {
    }

    /**
     * @param position  position at {@code updatedAt}, ms
     * @param updatedAt when the player reported it (elapsed realtime, ms)
     * @param speed     playback speed (1 = normal)
     * @param duration  song length in ms, 0 if unknown
     */
    public static long position(long position, long updatedAt, float speed, boolean playing, long now, long duration) {
        long p = position;
        if (playing && updatedAt > 0 && now > updatedAt) {
            p += (long) ((now - updatedAt) * (speed == 0 ? 1f : speed));
        }
        if (duration > 0) {
            p = Math.min(p, duration);
        }
        return Math.max(0, p);
    }

    /** 3:07, 1:02:05 */
    public static String time(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }
}
