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

        public Frame(int frequency, int[] pattern) {
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

    /** RC-5 half-bit time: a bit lasts 1.778 ms (64 carrier cycles at 36 kHz per half). */
    static final int RC5_HALF = 889;

    /**
     * Philips RC-5 (36 kHz), used by Airties set-top boxes: 14 bits in Manchester code (a 1 is
     * "off then on", a 0 is "on then off"): two start bits (the second is cleared for commands
     * 64-127), the toggle bit, the 5-bit system address and 6 command bits, MSB first.
     *
     * @param toggle flips on every new key press and stays the same while a key is held, so the
     *               receiver can tell a second press from a held key
     */
    public static Frame rc5(int system, int command, boolean toggle) {
        int bits = 1; // S1
        bits = (bits << 1) | (command < 64 ? 1 : 0); // S2 (field bit)
        bits = (bits << 1) | (toggle ? 1 : 0);
        bits = (bits << 5) | (system & 0x1F);
        bits = (bits << 6) | (command & 0x3F);
        // half-bit levels, then runs of equal levels
        boolean[] half = new boolean[28];
        for (int i = 0; i < 14; i++) {
            boolean one = ((bits >> (13 - i)) & 1) != 0;
            half[2 * i] = !one;
            half[2 * i + 1] = one;
        }
        int[] runs = new int[28];
        int n = 0;
        int start = half[0] ? 0 : 1; // the first start bit's leading "off" is not sent
        boolean level = half[start];
        int length = 0;
        for (int i = start; i < 28; i++) {
            if (half[i] == level) {
                length += RC5_HALF;
            } else {
                runs[n++] = length;
                level = half[i];
                length = RC5_HALF;
            }
        }
        if (level) {
            runs[n++] = length; // ends "on"; a trailing "off" is the gap to the next frame
        }
        int[] p = new int[n];
        System.arraycopy(runs, 0, p, 0, n);
        return new Frame(36_000, p);
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
