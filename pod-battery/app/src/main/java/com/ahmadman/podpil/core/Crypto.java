package com.ahmadman.podpil.core;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AirPods' two keys: the encryption key unlocks exact battery values in the broadcast, the
 * identity key (IRK) recognises the user's own AirPods among others nearby.
 */
public final class Crypto {

    private Crypto() {
    }

    /**
     * Replaces 10 % values with the exact ones from the encrypted part. Values that do not fit the
     * public 10 % value (wrong key, another pair of AirPods) are ignored.
     *
     * @return true when exact values were applied
     */
    @SuppressWarnings("GetInstance") // the AirPods use single-block AES; there is no other mode
    public static boolean decrypt(Status s, byte[] encKey) {
        if (s == null || s.encrypted == null || encKey == null || encKey.length != 16) {
            return false;
        }
        byte[] p;
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"));
            p = c.doFinal(s.encrypted);
        } catch (GeneralSecurityException e) {
            return false;
        }
        if (s.model().single) {
            int l = exact(p[1]);
            if (!fits(l, s.left)) {
                return false;
            }
            s.left = l;
            s.leftCharging = charging(p[1]);
        } else {
            int li = s.flipped ? 2 : 1;
            int ri = s.flipped ? 1 : 2;
            int l = exact(p[li]);
            int r = exact(p[ri]);
            int cs = exact(p[3]);
            if (!fits(l, s.left) || !fits(r, s.right) || !fits(cs, s.caseLevel)) {
                return false;
            }
            if (l == Status.UNKNOWN && r == Status.UNKNOWN && cs == Status.UNKNOWN) {
                return false;
            }
            if (l != Status.UNKNOWN) {
                s.left = l;
                s.leftCharging = charging(p[li]);
            }
            if (r != Status.UNKNOWN) {
                s.right = r;
                s.rightCharging = charging(p[ri]);
            }
            if (cs != Status.UNKNOWN) {
                s.caseLevel = cs;
                s.caseCharging = charging(p[3]);
            }
        }
        s.source = Status.Source.DECRYPTED;
        return true;
    }

    /** bits 0-6 = percent, bit 7 = charging; values above 100 mean "not available". */
    static int exact(byte b) {
        int v = b & 0x7F;
        return v > 100 ? Status.UNKNOWN : v;
    }

    static boolean charging(byte b) {
        return (b & 0x80) != 0;
    }

    /** An exact value must lie within one 10 % step of the public value. */
    static boolean fits(int exact, int decile) {
        if (exact == Status.UNKNOWN || decile == Status.UNKNOWN) {
            return true;
        }
        return Math.abs(exact - decile) <= 10;
    }

    /**
     * Does a resolvable private address belong to the AirPods with this identity key? Both byte
     * orders of the address are tried, as phones report it differently.
     */
    public static boolean ownAddress(String address, byte[] irk) {
        byte[] octets = parseAddress(address);
        if (octets == null || irk == null || irk.length != 16) {
            return false;
        }
        if (matches(octets, irk)) {
            return true;
        }
        byte[] rev = reverse(octets);
        return (rev[0] & 0xC0) == 0x40 && matches(rev, irk);
    }

    private static boolean matches(byte[] octets, byte[] irk) {
        byte[] rpa = reverse(octets); // little endian: hash in [0..2], prand in [3..5]
        byte[] prand = Arrays.copyOfRange(rpa, 3, 6);
        byte[] hash = Arrays.copyOfRange(rpa, 0, 3);
        byte[] ah = ah(irk, prand);
        return ah != null && Arrays.equals(hash, ah);
    }

    /** Bluetooth core spec random address hash; inputs and output little endian. */
    @SuppressWarnings("GetInstance") // Bluetooth's address hash is defined on single-block AES
    static byte[] ah(byte[] irk, byte[] prand) {
        byte[] padded = new byte[16];
        System.arraycopy(prand, 0, padded, 0, 3);
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(reverse(irk), "AES"));
            byte[] out = reverse(c.doFinal(reverse(padded)));
            return Arrays.copyOfRange(out, 0, 3);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    static byte[] parseAddress(String a) {
        if (a == null) {
            return null;
        }
        String[] parts = a.split(":");
        if (parts.length != 6) {
            return null;
        }
        byte[] out = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                out[i] = (byte) Integer.parseInt(parts[i], 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    public static byte[] reverse(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) {
            r[i] = b[b.length - 1 - i];
        }
        return r;
    }

    /** 32 hex digits (spaces, colons, "0x" and dashes allowed) to 16 bytes; null if invalid. */
    public static byte[] hexKey(String s) {
        if (s == null) {
            return null;
        }
        String h = s.trim().toLowerCase(Locale.ROOT).replace("0x", "").replaceAll("[\\s:<>-]", "");
        if (h.length() != 32 || !h.matches("[0-9a-f]+")) {
            return null;
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
