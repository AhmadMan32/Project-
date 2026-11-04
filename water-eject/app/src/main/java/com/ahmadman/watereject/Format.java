package com.ahmadman.watereject;

import java.util.Locale;

final class Format {

    private Format() {
    }

    /** Milliseconds as m:ss, rounded up so the countdown ends on 0:00. */
    static String time(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
