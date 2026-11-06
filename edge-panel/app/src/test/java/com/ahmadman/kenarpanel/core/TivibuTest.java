package com.ahmadman.kenarpanel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class TivibuTest {

    /** RC-5 read back the way a receiver does: Manchester halves of 889 µs. @return the 14 bits */
    public static int decodeRc5(int[] pattern) {
        StringBuilder halves = new StringBuilder("0"); // the first start bit's "off" half is not sent
        for (int i = 0; i < pattern.length; i++) {
            int units = Math.round(pattern[i] / 889f);
            assertTrue("timing " + pattern[i], (units == 1 || units == 2) && Math.abs(pattern[i] - 889 * units) < 5);
            char level = i % 2 == 0 ? '1' : '0';
            for (int u = 0; u < units; u++) {
                halves.append(level);
            }
        }
        while (halves.length() < 28) {
            halves.append('0'); // the last bit may end "off"
        }
        assertEquals("14 bits", 28, halves.length());
        int bits = 0;
        for (int i = 0; i < 28; i += 2) {
            String pair = halves.substring(i, i + 2);
            if (pair.equals("01")) {
                bits = (bits << 1) | 1;
            } else if (pair.equals("10")) {
                bits = bits << 1;
            } else {
                fail("not Manchester: " + halves);
            }
        }
        return bits;
    }

    @Test
    public void rc5FramesDecodeBackToSystemAndCommand() {
        for (int system : new int[]{0, 5, 27, 31}) {
            for (int command = 0; command < 128; command++) {
                for (boolean toggle : new boolean[]{false, true}) {
                    Ir.Frame f = Ir.rc5(system, command, toggle);
                    assertEquals(36_000, f.frequency);
                    assertEquals(0, f.pattern.length % 2 == 1 ? 0 : 1); // starts and ends "on"
                    int bits = decodeRc5(f.pattern);
                    assertEquals(1, bits >> 13); // S1
                    assertEquals(command < 64 ? 1 : 0, (bits >> 12) & 1); // S2 = field bit
                    assertEquals(toggle ? 1 : 0, (bits >> 11) & 1);
                    assertEquals(system, (bits >> 6) & 0x1F);
                    assertEquals(command & 0x3F, bits & 0x3F);
                    assertTrue(f.duration() <= 14 * 1778 && f.duration() > 12 * 1778); // ~24.9 ms
                }
            }
        }
    }

    @Test
    public void knownRc5Timings() {
        // system 0, command 0, toggle 0: 1 1 0 00000 000000
        Ir.Frame f = Ir.rc5(0, 0, false);
        assertEquals(889, f.pattern[0]); // second half of S1 ("on")
        assertEquals(889, f.pattern[1]); // first half of S2 ("off")
        assertEquals(1778, f.pattern[2]); // S2 "on" + T=0 "on"
    }

    /**
     * The bytes in the JP1 file "Airties Air 7120.rmdu" (RC-5, device 27), key by key: our
     * command numbers must give exactly these.
     */
    @Test
    public void keysMatchThePublishedAirtiesCodeTable() {
        Object[][] table = {
                {Tivibu.Key.DIGIT_0, 0xFC}, {Tivibu.Key.DIGIT_1, 0xF8}, {Tivibu.Key.DIGIT_2, 0xF4},
                {Tivibu.Key.DIGIT_3, 0xF0}, {Tivibu.Key.DIGIT_4, 0xEC}, {Tivibu.Key.DIGIT_5, 0xE8},
                {Tivibu.Key.DIGIT_6, 0xE4}, {Tivibu.Key.DIGIT_7, 0xE0}, {Tivibu.Key.DIGIT_8, 0xDC},
                {Tivibu.Key.DIGIT_9, 0xD8},
                {Tivibu.Key.VOLUME_UP, 0x04}, {Tivibu.Key.VOLUME_DOWN, 0x08}, {Tivibu.Key.MUTE, 0x0C},
                {Tivibu.Key.CHANNEL_UP, 0x7C}, {Tivibu.Key.CHANNEL_DOWN, 0x78}, {Tivibu.Key.LAST_CHANNEL, 0x74},
                {Tivibu.Key.POWER, 0xB0},
                {Tivibu.Key.UP, 0xD4}, {Tivibu.Key.DOWN, 0xCC}, {Tivibu.Key.LEFT, 0xC8}, {Tivibu.Key.RIGHT, 0xD0},
                {Tivibu.Key.OK, 0xC4}, {Tivibu.Key.MENU, 0xAC}, {Tivibu.Key.BACK, 0xC0},
                {Tivibu.Key.INFO, 0x98}, {Tivibu.Key.GUIDE, 0xA0}, {Tivibu.Key.PAGE_UP, 0x90},
                {Tivibu.Key.PAGE_DOWN, 0x8C},
                {Tivibu.Key.YELLOW, 0x54}, {Tivibu.Key.BLUE, 0x50}, {Tivibu.Key.RED, 0x5C}, {Tivibu.Key.GREEN, 0x58},
                {Tivibu.Key.FAVORITE, 0x70}, {Tivibu.Key.HELP, 0x94}, {Tivibu.Key.LANGUAGE, 0x68},
                {Tivibu.Key.MEDIA, 0x9C}, {Tivibu.Key.OPTIONS, 0xBC},
                {Tivibu.Key.PLAY, 0x28}, {Tivibu.Key.PAUSE, 0x20}, {Tivibu.Key.REWIND, 0x34},
                {Tivibu.Key.FORWARD, 0x2C}, {Tivibu.Key.RECORD, 0x64}, {Tivibu.Key.STOP, 0x24},
                {Tivibu.Key.PREVIOUS, 0x1C}, {Tivibu.Key.NEXT, 0x18}, {Tivibu.Key.LIST, 0x6C},
                {Tivibu.Key.SUBTITLE, 0x60}, {Tivibu.Key.TELETEXT, 0xB4}, {Tivibu.Key.TIMER, 0xB8},
                {Tivibu.Key.TV_RADIO, 0xA4}, {Tivibu.Key.VOD, 0xA8},
        };
        assertEquals("every key is in the table", Tivibu.Key.values().length, table.length);
        Set<Integer> commands = new HashSet<>();
        for (Object[] row : table) {
            Tivibu.Key k = (Tivibu.Key) row[0];
            assertEquals(k.name(), (int) (Integer) row[1], Tivibu.jp1Hex(k));
            assertTrue("one command per key", commands.add(k.command));
            assertTrue(k.command < 64); // all in the first field: S2 = 1
        }
        for (int d = 0; d <= 9; d++) {
            assertEquals(d, Tivibu.Key.digit(d).command);
        }
    }

    @Test
    public void eachPressFlipsTheToggleAndHoldingKeepsIt() {
        Tivibu.Sender s = new Tivibu.Sender();
        int a = decodeRc5(s.press(Tivibu.Key.VOLUME_UP).pattern);
        int held = decodeRc5(s.hold(Tivibu.Key.VOLUME_UP).pattern);
        int b = decodeRc5(s.press(Tivibu.Key.VOLUME_UP).pattern);
        assertEquals(a, held);
        assertNotEquals((a >> 11) & 1, (b >> 11) & 1);
        assertEquals(a & ~(1 << 11), b & ~(1 << 11));
        assertEquals(Tivibu.SYSTEM, (a >> 6) & 0x1F);
        assertEquals(62, a & 0x3F);
        assertTrue(Tivibu.Key.VOLUME_UP.repeats());
        assertTrue(!Tivibu.Key.POWER.repeats() && !Tivibu.Key.DIGIT_5.repeats());
    }
}
