package com.ahmadman.podpil.core;

/**
 * Apple's accessory protocol (AAP), spoken over a Bluetooth L2CAP channel (PSM 0x1001) while
 * the AirPods are connected. The AirPods report exact battery percentages on it and hand out
 * their identity and encryption keys.
 */
public final class Aap {

    public static final int PSM = 0x1001;

    public static final byte[] HANDSHAKE = bytes(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
    public static final byte[] NOTIFY_1 = bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xEF, 0xFF);
    public static final byte[] NOTIFY_2 = bytes(0x04, 0x00, 0x04, 0x00, 0x0F, 0x00, 0xFF, 0xFF, 0xFF, 0xFF);
    public static final byte[] INIT_EXT = bytes(0x04, 0x00, 0x04, 0x00, 0x4D, 0x00, 0xD7, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00);
    public static final byte[] KEY_REQUEST = bytes(0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00);

    static final int PACKET_CONNECT_RESPONSE = 0x0001;
    static final int PACKET_MESSAGE = 0x0004;
    static final int MSG_BATTERY = 0x0004;
    static final int MSG_KEYS = 0x0031;

    static final int TYPE_SINGLE = 0x01;
    static final int TYPE_RIGHT = 0x02;
    static final int TYPE_LEFT = 0x04;
    static final int TYPE_CASE = 0x08;

    static final int CHARGING = 0x01;
    static final int CHARGING_OPTIMIZED = 0x05;

    private Aap() {
    }

    public static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static int u16(byte[] f, int i) {
        return (f[i] & 0xFF) | ((f[i + 1] & 0xFF) << 8);
    }

    /** Kind of a received frame. */
    public enum Kind { CONNECTED, BATTERY, KEYS, OTHER }

    public static Kind kind(byte[] f) {
        if (f == null || f.length < 4) {
            return Kind.OTHER;
        }
        int packet = u16(f, 0);
        if (packet == PACKET_CONNECT_RESPONSE) {
            return f.length >= 6 && u16(f, 4) == 0 ? Kind.CONNECTED : Kind.OTHER;
        }
        if (packet != PACKET_MESSAGE || f.length < 6) {
            return Kind.OTHER;
        }
        switch (u16(f, 4)) {
            case MSG_BATTERY:
                return Kind.BATTERY;
            case MSG_KEYS:
                return Kind.KEYS;
            default:
                return Kind.OTHER;
        }
    }

    /**
     * Applies a battery message (count, then 5-byte entries: type, -, percent, charging, -) to
     * the status. Entries that are missing keep their old value.
     *
     * @return true when at least one value was read
     */
    public static boolean battery(byte[] f, Status s) {
        if (kind(f) != Kind.BATTERY || f.length < 7) {
            return false;
        }
        int count = f[6] & 0xFF;
        boolean any = false;
        for (int i = 0; i < count; i++) {
            int o = 7 + i * 5;
            if (o + 3 >= f.length) {
                break;
            }
            int type = f[o] & 0xFF;
            int level = f[o + 2] & 0xFF;
            int state = f[o + 3] & 0xFF;
            boolean charging = state == CHARGING || state == CHARGING_OPTIMIZED;
            if (level > 100) {
                level = Status.UNKNOWN; // 127 after the case closes, 255 disconnected
            }
            switch (type) {
                case TYPE_LEFT:
                case TYPE_SINGLE:
                    s.left = level;
                    s.leftCharging = charging;
                    break;
                case TYPE_RIGHT:
                    s.right = level;
                    s.rightCharging = charging;
                    break;
                case TYPE_CASE:
                    s.caseLevel = level;
                    s.caseCharging = charging;
                    break;
                default:
                    continue;
            }
            any = true;
        }
        if (any) {
            s.source = Status.Source.AAP;
        }
        return any;
    }

    /** Identity (IRK) and encryption key from a keys message. */
    public static final class Keys {
        public byte[] irk;
        public byte[] enc;
    }

    /** count, then entries: type, -, length, -, data. Type 0x01 = IRK, 0x04 = encryption key. */
    public static Keys keys(byte[] f) {
        if (kind(f) != Kind.KEYS || f.length < 7) {
            return null;
        }
        Keys k = new Keys();
        int count = f[6] & 0xFF;
        int o = 7;
        for (int i = 0; i < count && o + 4 <= f.length; i++) {
            int type = f[o] & 0xFF;
            int len = f[o + 2] & 0xFF;
            int start = o + 4;
            if (start + len > f.length) {
                break;
            }
            byte[] data = new byte[len];
            System.arraycopy(f, start, data, 0, len);
            if (type == 0x01 && len == 16) {
                k.irk = data;
            } else if (type == 0x04 && len == 16) {
                k.enc = data;
            }
            o = start + len;
        }
        return k.irk == null && k.enc == null ? null : k;
    }
}
