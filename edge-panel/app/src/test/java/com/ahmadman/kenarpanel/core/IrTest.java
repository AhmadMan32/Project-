package com.ahmadman.kenarpanel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IrTest {

    /** Reads the 32 data bits of a NEC/Samsung frame back, in the order they are sent. */
    static long pulseDistanceBits(int[] p) {
        long v = 0;
        for (int i = 0; i < 32; i++) {
            int mark = p[2 + 2 * i];
            int space = p[3 + 2 * i];
            assertEquals(563, mark);
            v = (v << 1) | (space > 1000 ? 1 : 0);
        }
        return v;
    }

    @Test
    public void lgUsesNecAndMatchesKnownCodes() {
        // well-known LG codes as written in LIRC files (bits in sending order)
        assertEquals(0x20DF10EFL, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.POWER, false).pattern));
        assertEquals(0x20DF40BFL, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.VOL_UP, false).pattern));
        assertEquals(0x20DFC03FL, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.VOL_DOWN, false).pattern));
        assertEquals(0x20DF906FL, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.MUTE, false).pattern));
        assertEquals(0x20DFD02FL, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.INPUT, false).pattern));
        assertEquals(0x20DF08F7L, pulseDistanceBits(Remotes.LG.frame(Remotes.Key.N0, false).pattern));
        Ir.Frame f = Remotes.LG.frame(Remotes.Key.POWER, false);
        assertEquals(38_000, f.frequency);
        assertEquals(9000, f.pattern[0]);
        assertEquals(4500, f.pattern[1]);
        assertEquals(67, f.pattern.length); // leader 2 + 32 bits * 2 + stop
        assertEquals(563, f.pattern[66]);
    }

    @Test
    public void samsungUsesItsOwnLeaderAndMatchesKnownCodes() {
        Ir.Frame f = Remotes.SAMSUNG.frame(Remotes.Key.POWER, false);
        assertEquals(4500, f.pattern[0]);
        assertEquals(4500, f.pattern[1]);
        assertEquals(0xE0E040BFL, pulseDistanceBits(f.pattern));
        assertEquals(0xE0E0E01FL, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.VOL_UP, false).pattern));
        assertEquals(0xE0E0D02FL, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.VOL_DOWN, false).pattern));
        assertEquals(0xE0E0F00FL, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.MUTE, false).pattern));
        assertEquals(0xE0E048B7L, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.CH_UP, false).pattern));
        assertEquals(0xE0E0807FL, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.INPUT, false).pattern));
        assertEquals(0xE0E020DFL, pulseDistanceBits(Remotes.SAMSUNG.frame(Remotes.Key.N1, false).pattern));
        assertTrue(f.duration() < 100_000);
    }

    @Test
    public void sonySendsTwelveBitsThreeTimes() {
        Ir.Frame f = Remotes.SONY.frame(Remotes.Key.POWER, false);
        assertEquals(40_000, f.frequency);
        // 3 frames of (header 2 + 12 bits * 2), last trailing space dropped
        assertEquals(3 * 26 - 1, f.pattern.length);
        long bits = 0;
        for (int i = 0; i < 12; i++) {
            bits = (bits << 1) | (f.pattern[2 + 2 * i] == 1200 ? 1 : 0);
        }
        assertEquals(0xA90, bits); // the well-known Sony TV power code
        assertEquals(2400, f.pattern[26]); // second frame header
        // frames start 45 ms apart
        long first = 0;
        for (int i = 0; i < 26; i++) {
            first += f.pattern[i];
        }
        assertEquals(45_000, first);
        assertTrue(f.duration() < 2_000_000);
    }

    @Test
    public void philipsRc5IsManchesterCoded() {
        Ir.Frame f = Remotes.PHILIPS.frame(Remotes.Key.POWER, false);
        assertEquals(36_000, f.frequency);
        // rebuild half-bit levels and decode 14 bits
        StringBuilder levels = new StringBuilder("0"); // the leading space that was dropped
        boolean on = true;
        for (int d : f.pattern) {
            int n = d / 889;
            assertEquals(0, d % 889);
            for (int k = 0; k < n; k++) {
                levels.append(on ? '1' : '0');
            }
            on = !on;
        }
        while (levels.length() < 28) {
            levels.append('0');
        }
        int value = 0;
        for (int b = 0; b < 14; b++) {
            String pair = levels.substring(2 * b, 2 * b + 2);
            assertTrue(pair.equals("01") || pair.equals("10"));
            value = (value << 1) | (pair.equals("01") ? 1 : 0);
        }
        // start 1, field 1, toggle 0, address 0, command 12 (standby)
        assertEquals(0b11_0_00000_001100, value);
        Ir.Frame toggled = Remotes.PHILIPS.frame(Remotes.Key.POWER, true);
        assertFalse(java.util.Arrays.equals(f.pattern, toggled.pattern));
        // extended command (cursor up = 80) clears the field bit
        Ir.Frame up = Remotes.PHILIPS.frame(Remotes.Key.UP, false);
        assertTrue(up.pattern.length > 0);
    }

    @Test
    public void everyKeyFitsAndMissingKeysAreNull() {
        for (Remotes.Brand b : Remotes.ALL) {
            for (Remotes.Key k : Remotes.Key.values()) {
                Ir.Frame f = b.frame(k, false);
                if (f == null) {
                    assertFalse(b.has(k));
                    continue;
                }
                assertTrue(f.pattern.length % 2 == 1); // starts and ends with "on"
                assertTrue(f.duration() < 2_000_000);
                for (int d : f.pattern) {
                    assertTrue(d > 0);
                }
            }
            assertTrue(b.name, b.has(Remotes.Key.POWER));
            assertTrue(b.has(Remotes.Key.VOL_UP));
            assertTrue(b.has(Remotes.Key.N9));
        }
        assertNull(Remotes.SONY.frame(Remotes.Key.BACK, false));
        assertEquals(Remotes.LG, Remotes.byId("lg"));
        assertEquals(Remotes.SAMSUNG, Remotes.byId("unknown"));
        assertTrue(Remotes.Key.VOL_UP.repeats());
        assertFalse(Remotes.Key.POWER.repeats());
    }

    @Test
    public void carrierFrequencyIsAdjustedToThePhone() {
        assertEquals(38_000, Ir.bestFrequency(38_000, new int[][]{{30_000, 60_000}}));
        assertEquals(38_000, Ir.bestFrequency(38_000, null));
        assertEquals(37_900, Ir.bestFrequency(38_000, new int[][]{{36_000, 37_900}, {40_000, 40_000}}));
        assertEquals(40_000, Ir.bestFrequency(40_000, new int[][]{{36_000, 37_900}, {40_000, 40_000}}));
    }
}
