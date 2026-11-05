package com.ahmadman.kenarpanel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IrTest {

    /** Reads the 32 data bits of a NEC frame back, in the order they are sent. */
    static long bits(int[] p) {
        long v = 0;
        for (int i = 0; i < 32; i++) {
            assertEquals(563, p[2 + 2 * i]);
            v = (v << 1) | (p[3 + 2 * i] > 1000 ? 1 : 0);
        }
        return v;
    }

    @Test
    public void lgKeysMatchTheKnownCodes() {
        // as written in LIRC files / IR code lists (bits in sending order)
        assertEquals(0x20DF10EFL, bits(Remotes.Key.POWER.frame().pattern));
        assertEquals(0x20DF738CL, bits(Remotes.Key.HDMI1.frame().pattern));
        assertEquals(0x20DF33CCL, bits(Remotes.Key.HDMI2.frame().pattern));
    }

    @Test
    public void necFrameShape() {
        Ir.Frame f = Remotes.Key.POWER.frame();
        assertEquals(38_000, f.frequency);
        assertEquals(9000, f.pattern[0]);
        assertEquals(4500, f.pattern[1]);
        assertEquals(67, f.pattern.length); // leader + 32 bits + stop: starts and ends "on"
        assertEquals(563, f.pattern[66]);
        for (Remotes.Key k : Remotes.Key.values()) {
            long d = k.frame().duration();
            assertTrue(d > 50_000 && d < 80_000); // a NEC frame is about 54-68 ms
        }
    }

    @Test
    public void carrierFrequencyIsAdjustedToThePhone() {
        assertEquals(38_000, Ir.bestFrequency(38_000, new int[][]{{30_000, 60_000}}));
        assertEquals(38_000, Ir.bestFrequency(38_000, null));
        assertEquals(37_900, Ir.bestFrequency(38_000, new int[][]{{36_000, 37_900}, {40_000, 40_000}}));
    }
}
