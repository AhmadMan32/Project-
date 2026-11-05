package com.ahmadman.kenarpanel.core;

import java.util.EnumMap;
import java.util.Map;

/** TV brands and their remote control codes. */
public final class Remotes {

    private Remotes() {
    }

    public enum Key {
        POWER, INPUT, MUTE, VOL_UP, VOL_DOWN, CH_UP, CH_DOWN, UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU, EXIT,
        N0, N1, N2, N3, N4, N5, N6, N7, N8, N9;

        /** Volume and channel keys repeat while held. */
        public boolean repeats() {
            return this == VOL_UP || this == VOL_DOWN || this == CH_UP || this == CH_DOWN
                    || this == UP || this == DOWN || this == LEFT || this == RIGHT;
        }

        public static Key digit(int d) {
            return values()[N0.ordinal() + d];
        }
    }

    public enum Protocol { NEC, SAMSUNG, SONY12, RC5 }

    public static final class Brand {
        public final String id;
        public final String name;
        public final Protocol protocol;
        public final int address;
        final Map<Key, Integer> codes = new EnumMap<>(Key.class);

        Brand(String id, String name, Protocol protocol, int address) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.address = address;
        }

        Brand put(Key k, int code) {
            codes.put(k, code);
            return this;
        }

        public boolean has(Key k) {
            return codes.containsKey(k);
        }

        /** @param toggle RC5 alternates this bit on every new press */
        public Ir.Frame frame(Key k, boolean toggle) {
            Integer c = codes.get(k);
            if (c == null) {
                return null;
            }
            switch (protocol) {
                case SAMSUNG:
                    return Ir.samsung(address, c);
                case SONY12:
                    return Ir.sony12(address, c);
                case RC5:
                    return Ir.rc5(address, c, toggle);
                default:
                    return Ir.nec(address, c);
            }
        }
    }

    public static final Brand SAMSUNG = digits(new Brand("samsung", "Samsung", Protocol.SAMSUNG, 0x07)
            .put(Key.POWER, 0x02).put(Key.INPUT, 0x01).put(Key.MUTE, 0x0F)
            .put(Key.VOL_UP, 0x07).put(Key.VOL_DOWN, 0x0B).put(Key.CH_UP, 0x12).put(Key.CH_DOWN, 0x10)
            .put(Key.UP, 0x60).put(Key.DOWN, 0x61).put(Key.LEFT, 0x65).put(Key.RIGHT, 0x62).put(Key.OK, 0x68)
            .put(Key.BACK, 0x58).put(Key.HOME, 0x79).put(Key.MENU, 0x1A).put(Key.EXIT, 0x2D),
            0x11, 0x04, 0x05, 0x06, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x0E);

    public static final Brand LG = digits(new Brand("lg", "LG", Protocol.NEC, 0x04)
            .put(Key.POWER, 0x08).put(Key.INPUT, 0x0B).put(Key.MUTE, 0x09)
            .put(Key.VOL_UP, 0x02).put(Key.VOL_DOWN, 0x03).put(Key.CH_UP, 0x00).put(Key.CH_DOWN, 0x01)
            .put(Key.UP, 0x40).put(Key.DOWN, 0x41).put(Key.LEFT, 0x07).put(Key.RIGHT, 0x06).put(Key.OK, 0x44)
            .put(Key.BACK, 0x28).put(Key.HOME, 0x7C).put(Key.MENU, 0x43).put(Key.EXIT, 0x5B),
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19);

    public static final Brand SONY = digits(new Brand("sony", "Sony", Protocol.SONY12, 0x01)
            .put(Key.POWER, 21).put(Key.INPUT, 37).put(Key.MUTE, 20)
            .put(Key.VOL_UP, 18).put(Key.VOL_DOWN, 19).put(Key.CH_UP, 16).put(Key.CH_DOWN, 17)
            .put(Key.UP, 116).put(Key.DOWN, 117).put(Key.LEFT, 52).put(Key.RIGHT, 51).put(Key.OK, 101)
            .put(Key.MENU, 96),
            9, 0, 1, 2, 3, 4, 5, 6, 7, 8);

    public static final Brand PHILIPS = digits(new Brand("philips", "Philips", Protocol.RC5, 0x00)
            .put(Key.POWER, 12).put(Key.INPUT, 56).put(Key.MUTE, 13)
            .put(Key.VOL_UP, 16).put(Key.VOL_DOWN, 17).put(Key.CH_UP, 32).put(Key.CH_DOWN, 33)
            .put(Key.UP, 80).put(Key.DOWN, 81).put(Key.LEFT, 85).put(Key.RIGHT, 86).put(Key.OK, 87)
            .put(Key.MENU, 82),
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    public static final Brand[] ALL = {SAMSUNG, LG, SONY, PHILIPS};

    private static Brand digits(Brand b, int... d) {
        for (int i = 0; i < 10; i++) {
            b.put(Key.digit(i), d[i]);
        }
        return b;
    }

    public static Brand byId(String id) {
        for (Brand b : ALL) {
            if (b.id.equals(id)) {
                return b;
            }
        }
        return SAMSUNG;
    }
}
