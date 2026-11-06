package com.ahmadman.kenarpanel.core;

/**
 * Keys of the Tivibu remote for Airties IPTV boxes (Air 7100, 7120, 7200, 7200i, 7205: the
 * boxes Tivibu and Digiturk Play hand out share one remote). They listen on RC-5 system 27.
 *
 * <p>The command numbers come from the JP1 device upgrade "Airties Air 7120" (hifiremote
 * deviceupgrades, Cable), recorded from the original remote: RC-5, device 27, 36 kHz. In that
 * file each key is a byte whose top 6 bits are the complemented command ({@link #jp1Hex}).
 */
public final class Tivibu {

    private Tivibu() {
    }

    public static final int SYSTEM = 27;

    public enum Key {
        DIGIT_0(0), DIGIT_1(1), DIGIT_2(2), DIGIT_3(3), DIGIT_4(4),
        DIGIT_5(5), DIGIT_6(6), DIGIT_7(7), DIGIT_8(8), DIGIT_9(9),
        UP(10), RIGHT(11), DOWN(12), LEFT(13), OK(14),
        /** "exit": back out of menus. */
        BACK(15),
        OPTIONS(16), TIMER(17), TELETEXT(18),
        POWER(19),
        MENU(20), VOD(21), TV_RADIO(22), GUIDE(23), MEDIA(24), INFO(25), HELP(26),
        PAGE_UP(27), PAGE_DOWN(28), FAVORITE(35), LIST(36), LANGUAGE(37), RECORD(38), SUBTITLE(39),
        RED(40), GREEN(41), YELLOW(42), BLUE(43),
        CHANNEL_UP(32), CHANNEL_DOWN(33), LAST_CHANNEL(34),
        REWIND(50), FORWARD(52), PLAY(53), STOP(54), PAUSE(55), PREVIOUS(56), NEXT(57),
        MUTE(60), VOLUME_DOWN(61), VOLUME_UP(62);

        public final int command;

        Key(int command) {
            this.command = command;
        }

        public static Key digit(int d) {
            return values()[d]; // DIGIT_0 .. DIGIT_9 come first
        }

        /** Held keys keep sending (volume, channels, arrows); the others are single presses. */
        public boolean repeats() {
            switch (this) {
                case VOLUME_UP:
                case VOLUME_DOWN:
                case CHANNEL_UP:
                case CHANNEL_DOWN:
                case UP:
                case DOWN:
                case LEFT:
                case RIGHT:
                case REWIND:
                case FORWARD:
                    return true;
                default:
                    return false;
            }
        }
    }

    /** The byte a JP1 remote stores for this key (to check against the published file). */
    public static int jp1Hex(Key k) {
        return (~k.command & 0x3F) << 2;
    }

    /** Remembers the toggle bit, as a real remote does. */
    public static final class Sender {
        private boolean toggle;

        /** A new press: the toggle bit flips. */
        public synchronized Ir.Frame press(Key k) {
            toggle = !toggle;
            return Ir.rc5(SYSTEM, k.command, toggle);
        }

        /** The key is still held: same toggle bit, so the box sees one long press. */
        public synchronized Ir.Frame hold(Key k) {
            return Ir.rc5(SYSTEM, k.command, toggle);
        }
    }

    /** A real remote sends the frame again every 113.778 ms (64 bit times) while a key is held. */
    public static final int REPEAT_MS = 114;
}
