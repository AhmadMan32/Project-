package com.ahmadman.kenarpanel.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Infrared remote protocols as on/off timings in microseconds, the format Android's
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

    // NEC / Samsung bit timings
    static final int BIT_MARK = 563;
    static final int ZERO_SPACE = 563;
    static final int ONE_SPACE = 1688;

    /** NEC (LG and many others): 9 ms + 4.5 ms leader, address, ~address, command, ~command, LSB first. */
    public static Frame nec(int address, int command) {
        return pulseDistance(38_000, 9000, 4500, new int[]{address & 0xFF, ~address & 0xFF, command & 0xFF, ~command & 0xFF});
    }

    /** NEC with a 16-bit address (extended NEC). */
    public static Frame necExtended(int address16, int command) {
        return pulseDistance(38_000, 9000, 4500,
                new int[]{address16 & 0xFF, (address16 >> 8) & 0xFF, command & 0xFF, ~command & 0xFF});
    }

    /** Samsung32 (Samsung TVs): 4.5 ms + 4.5 ms leader, address twice, command, ~command, LSB first. */
    public static Frame samsung(int address, int command) {
        return pulseDistance(38_000, 4500, 4500, new int[]{address & 0xFF, address & 0xFF, command & 0xFF, ~command & 0xFF});
    }

    private static Frame pulseDistance(int freq, int leaderMark, int leaderSpace, int[] bytes) {
        int[] p = new int[2 + bytes.length * 16 + 1];
        int i = 0;
        p[i++] = leaderMark;
        p[i++] = leaderSpace;
        for (int b : bytes) {
            for (int bit = 0; bit < 8; bit++) {
                p[i++] = BIT_MARK;
                p[i++] = ((b >> bit) & 1) != 0 ? ONE_SPACE : ZERO_SPACE;
            }
        }
        p[i] = BIT_MARK; // stop bit
        return new Frame(freq, p);
    }

    /**
     * Sony SIRC 12-bit (40 kHz): 2.4 ms header, 7 command bits then 5 address bits, LSB first,
     * 1 = 1.2 ms, 0 = 0.6 ms, each followed by 0.6 ms; sent three times, 45 ms apart.
     */
    public static Frame sony12(int address, int command) {
        List<Integer> one = new ArrayList<>();
        one.add(2400);
        one.add(600);
        int bits = (command & 0x7F) | ((address & 0x1F) << 7);
        for (int b = 0; b < 12; b++) {
            one.add(((bits >> b) & 1) != 0 ? 1200 : 600);
            one.add(600);
        }
        int frame = 0;
        for (int d : one) {
            frame += d;
        }
        List<Integer> all = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            for (int k = 0; k < one.size(); k++) {
                int d = one.get(k);
                if (k == one.size() - 1) {
                    if (r == 2) {
                        break; // end on a mark
                    }
                    d += 45_000 - frame; // gap to the next frame
                }
                all.add(d);
            }
        }
        return new Frame(40_000, toArray(all));
    }

    /**
     * Philips RC5 (36 kHz): Manchester coded, 889 µs half bits; start bit, field bit (inverted
     * command bit 6), toggle, 5 address bits, 6 command bits, MSB first. "1" = space then mark.
     */
    public static Frame rc5(int address, int command, boolean toggle) {
        int[] bits = new int[14];
        bits[0] = 1;
        bits[1] = (command & 0x40) == 0 ? 1 : 0;
        bits[2] = toggle ? 1 : 0;
        for (int b = 0; b < 5; b++) {
            bits[3 + b] = (address >> (4 - b)) & 1;
        }
        for (int b = 0; b < 6; b++) {
            bits[8 + b] = (command >> (5 - b)) & 1;
        }
        // half-bit levels: true = carrier on
        boolean[] half = new boolean[28];
        for (int b = 0; b < 14; b++) {
            half[2 * b] = bits[b] == 0;
            half[2 * b + 1] = bits[b] == 1;
        }
        List<Integer> out = new ArrayList<>();
        int run = 0;
        boolean level = half[0];
        for (boolean h : half) {
            if (h == level) {
                run += 889;
            } else {
                out.add(run);
                level = h;
                run = 889;
            }
        }
        out.add(run);
        // must start with "on" and end with "on": drop a leading or trailing space
        if (!half[0]) {
            out.remove(0);
        }
        if (!half[27]) {
            out.remove(out.size() - 1);
        }
        return new Frame(36_000, toArray(out));
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
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
