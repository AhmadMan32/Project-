package com.ahmadman.kenarpanel.core;

/**
 * Infrared remote signals as on/off timings in microseconds, the format Android's
 * ConsumerIrManager.transmit() takes (starts with "on", alternates).
 */
public final class Ir {

    private Ir() {
    }

    /** One transmission: carrier frequency and timings. */
    public static final class Frame {
        public final int frequency;
        public final int[] pattern;

        Frame(int frequency, int[] pattern) {
            this.frequency = frequency;
            this.pattern = pattern;
        }

        /** Total length in microseconds (Android allows at most 2 seconds). */
        public long duration() {
            long d = 0;
            for (int p : pattern) {
                d += p;
            }
            return d;
        }
    }

    static final int BIT_MARK = 563;
    static final int ZERO_SPACE = 563;
    static final int ONE_SPACE = 1688;

    /**
     * NEC, used by LG TVs (38 kHz): 9 ms + 4.5 ms leader, then address, ~address, command,
     * ~command, each byte LSB first, and a stop bit.
     */
    public static Frame nec(int address, int command) {
        int[] bytes = {address & 0xFF, ~address & 0xFF, command & 0xFF, ~command & 0xFF};
        int[] p = new int[2 + 32 * 2 + 1];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[i++] = BIT_MARK;
                p[i++] = ((b >> bit) & 1) != 0 ? ONE_SPACE : ZERO_SPACE;
            }
        }
        p[i] = BIT_MARK;
        return new Frame(38_000, p);
    }

    /** Picks the phone's supported carrier closest to the wanted one (some emitters have fixed ranges). */
    public static int bestFrequency(int wanted, int[][] ranges) {
        if (ranges == null || ranges.length == 0) {
            return wanted;
        }
        int best = wanted;
        long bestDist = Long.MAX_VALUE;
        for (int[] r : ranges) {
            if (r == null || r.length < 2) {
                continue;
            }
            if (wanted >= r[0] && wanted <= r[1]) {
                return wanted;
            }
            int candidate = wanted < r[0] ? r[0] : r[1];
            long dist = Math.abs((long) candidate - wanted);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }
}
