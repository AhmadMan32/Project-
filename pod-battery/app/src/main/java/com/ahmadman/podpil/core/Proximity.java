package com.ahmadman.podpil.core;

/**
 * Decodes Apple's "proximity pairing" Bluetooth LE broadcast (manufacturer data of company
 * 0x004C, type 0x07) that AirPods and Beats send several times a second.
 *
 * <pre>
 * byte 0      0x07 type, byte 1 0x19 length, byte 2 0x01 prefix
 * byte 3-4    model (big endian, e.g. 0x1420 AirPods Pro 2)
 * byte 5      status: bit 5 = left pod is the broadcaster (else values are swapped),
 *             bit 1 / bit 3 = in ear, bit 6 = broadcaster in case, bit 2 = both in case
 * byte 6      battery nibbles, 0..10 = 0..100 %, 15 = unknown
 * byte 7      high nibble charging flags (bit 0/1 pods, bit 2 case), low nibble case battery
 * byte 8      bit 3: lid closed
 * byte 11-26  encrypted part (exact battery, needs the AirPods' encryption key)
 * </pre>
 */
public final class Proximity {

    public static final int APPLE = 0x004C;
    public static final int TYPE = 0x07;
    public static final int LENGTH = 27;

    private Proximity() {
    }

    /** @return null when the data is not a proximity-pairing message */
    public static Status parse(byte[] d) {
        if (d == null || d.length < LENGTH || (d[0] & 0xFF) != TYPE || (d[1] & 0xFF) != 0x19 || (d[2] & 0xFF) != 0x01) {
            return null;
        }
        Status s = new Status();
        s.modelId = ((d[3] & 0xFF) << 8) | (d[4] & 0xFF);
        int status = d[5] & 0xFF;
        int pods = d[6] & 0xFF;
        int flags = (d[7] & 0xF0) >> 4;
        int caseNibble = d[7] & 0x0F;
        boolean flipped = (status & 0x20) == 0;
        boolean thisInCase = (status & 0x40) != 0;
        s.flipped = flipped;
        if (s.model().single) {
            s.left = level(pods & 0x0F);
            s.leftCharging = (flags & 0x01) != 0;
        } else {
            s.left = level(flipped ? pods >> 4 : pods & 0x0F);
            s.right = level(flipped ? pods & 0x0F : pods >> 4);
            s.caseLevel = level(caseNibble);
            s.leftCharging = (flags & (flipped ? 0x02 : 0x01)) != 0;
            s.rightCharging = (flags & (flipped ? 0x01 : 0x02)) != 0;
            s.caseCharging = (flags & 0x04) != 0;
            boolean swapEar = flipped ^ thisInCase;
            s.leftInEar = (status & (swapEar ? 0x08 : 0x02)) != 0;
            s.rightInEar = (status & (swapEar ? 0x02 : 0x08)) != 0;
            s.bothInCase = (status & 0x04) != 0;
            if (thisInCase || s.bothInCase) {
                s.lidOpen = ((d[8] >> 3) & 0x01) == 0;
            }
        }
        byte[] enc = new byte[16];
        System.arraycopy(d, d.length - 16, enc, 0, 16);
        s.encrypted = enc;
        s.source = Status.Source.BROADCAST;
        return s;
    }

    static int level(int nibble) {
        if (nibble == 15) {
            return Status.UNKNOWN;
        }
        return Math.min(nibble, 10) * 10;
    }
}
