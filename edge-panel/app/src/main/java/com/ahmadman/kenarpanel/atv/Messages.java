package com.ahmadman.kenarpanel.atv;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;

/**
 * Messages of the Android TV Remote protocol v2, as used by Google's TV remote apps:
 * "polo" pairing on port 6467 and the remote channel on port 6466.
 */
public final class Messages {

    private Messages() {
    }

    public static final int PAIRING_PORT = 6467;
    public static final int REMOTE_PORT = 6466;
    public static final String SERVICE_TYPE = "_androidtvremote2._tcp";

    // ---------------------------------------------------------------------------------------
    // Pairing (polo.proto: OuterMessage)

    static final int STATUS_OK = 200;
    static final int ENCODING_HEXADECIMAL = 3;
    static final int ROLE_INPUT = 1;

    public enum Pairing { REQUEST_ACK, OPTIONS, CONFIGURATION_ACK, SECRET_ACK, ERROR, OTHER }

    private static Proto.Writer outer() {
        return new Proto.Writer().varint(1, 2).varint(2, STATUS_OK); // protocol_version, status
    }

    private static Proto.Writer hexEncoding() {
        return new Proto.Writer().varint(1, ENCODING_HEXADECIMAL).varint(2, 6); // type, symbol_length
    }

    public static byte[] pairingRequest(String clientName) {
        Proto.Writer req = new Proto.Writer().string(1, "atvremote").string(2, clientName);
        return outer().message(10, req).toByteArray();
    }

    public static byte[] pairingOptions() {
        Proto.Writer options = new Proto.Writer().message(1, hexEncoding()).varint(3, ROLE_INPUT);
        return outer().message(20, options).toByteArray();
    }

    public static byte[] pairingConfiguration() {
        Proto.Writer cfg = new Proto.Writer().message(1, hexEncoding()).varint(2, ROLE_INPUT);
        return outer().message(30, cfg).toByteArray();
    }

    public static byte[] pairingSecret(byte[] secret) {
        return outer().message(40, new Proto.Writer().bytes(1, secret)).toByteArray();
    }

    public static Pairing pairingKind(byte[] msg) throws IOException {
        List<Proto.Field> f = Proto.parse(msg);
        Proto.Field status = Proto.find(f, 2);
        if (status == null || status.value != STATUS_OK) {
            return Pairing.ERROR;
        }
        if (Proto.find(f, 11) != null) {
            return Pairing.REQUEST_ACK;
        }
        if (Proto.find(f, 20) != null) {
            return Pairing.OPTIONS;
        }
        if (Proto.find(f, 31) != null) {
            return Pairing.CONFIGURATION_ACK;
        }
        if (Proto.find(f, 41) != null) {
            return Pairing.SECRET_ACK;
        }
        return Pairing.OTHER;
    }

    /** The code the TV shows: 6 hexadecimal characters. */
    public static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        return c.matches("[0-9A-F]{6}") ? c : null;
    }

    /**
     * The pairing secret: SHA-256 over both public keys (modulus and exponent, unsigned big-endian
     * bytes) and the last 4 characters of the code. The code's first 2 characters must equal the
     * hash's first byte; otherwise the code was mistyped.
     *
     * @return the secret, or null when the code does not match
     */
    public static byte[] pairingSecret(RSAPublicKey client, RSAPublicKey server, String code) {
        String c = normalizeCode(code);
        if (c == null) {
            return null;
        }
        try {
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            h.update(unsigned(client.getModulus()));
            h.update(unsigned(client.getPublicExponent()));
            h.update(unsigned(server.getModulus()));
            h.update(unsigned(server.getPublicExponent()));
            h.update(hex(c.substring(2)));
            byte[] hash = h.digest();
            return (hash[0] & 0xFF) == Integer.parseInt(c.substring(0, 2), 16) ? hash : null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Big-endian magnitude without leading zero bytes (like Python's bytes.fromhex(f"{n:X}")). */
    static byte[] unsigned(BigInteger n) {
        byte[] b = n.toByteArray();
        int start = 0;
        while (start < b.length - 1 && b[start] == 0) {
            start++;
        }
        byte[] out = new byte[b.length - start];
        System.arraycopy(b, start, out, 0, out.length);
        return out;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Remote channel (remotemessage.proto: RemoteMessage)

    public static final int FEATURE_PING = 1;
    public static final int FEATURE_KEY = 1 << 1;
    public static final int FEATURE_POWER = 1 << 5;
    public static final int FEATURE_VOLUME = 1 << 6;
    public static final int FEATURE_APP_LINK = 1 << 9;
    /** What this remote uses. */
    public static final int FEATURES = FEATURE_PING | FEATURE_KEY | FEATURE_POWER | FEATURE_VOLUME | FEATURE_APP_LINK;

    // Android key codes
    public static final int KEY_HOME = 3;
    public static final int KEY_BACK = 4;
    public static final int KEY_DPAD_UP = 19;
    public static final int KEY_DPAD_DOWN = 20;
    public static final int KEY_DPAD_LEFT = 21;
    public static final int KEY_DPAD_RIGHT = 22;
    public static final int KEY_DPAD_CENTER = 23;
    public static final int KEY_VOLUME_UP = 24;
    public static final int KEY_VOLUME_DOWN = 25;
    public static final int KEY_POWER = 26;
    public static final int KEY_VOLUME_MUTE = 164;

    public static final int DIRECTION_SHORT = 3;

    public enum Remote { CONFIGURE, SET_ACTIVE, PING, START, ERROR, OTHER }

    /** A parsed remote message: its kind and the one number that matters for it. */
    public static final class Incoming {
        public final Remote kind;
        /** CONFIGURE: features of the TV; PING: val1; START: 1 = screen on. */
        public final long value;

        Incoming(Remote kind, long value) {
            this.kind = kind;
            this.value = value;
        }
    }

    public static Incoming remote(byte[] msg) throws IOException {
        List<Proto.Field> f = Proto.parse(msg);
        Proto.Field x;
        if ((x = Proto.find(f, 1)) != null) {
            Proto.Field code = Proto.find(Proto.parse(x.bytes), 1);
            return new Incoming(Remote.CONFIGURE, code == null ? 0 : code.value);
        }
        if (Proto.find(f, 2) != null) {
            return new Incoming(Remote.SET_ACTIVE, 0);
        }
        if ((x = Proto.find(f, 8)) != null) {
            Proto.Field v = Proto.find(Proto.parse(x.bytes), 1);
            return new Incoming(Remote.PING, v == null ? 0 : v.value);
        }
        if ((x = Proto.find(f, 40)) != null) {
            Proto.Field v = Proto.find(Proto.parse(x.bytes), 1);
            return new Incoming(Remote.START, v == null ? 0 : v.value);
        }
        if (Proto.find(f, 3) != null) {
            return new Incoming(Remote.ERROR, 0);
        }
        return new Incoming(Remote.OTHER, 0);
    }

    /** Answer to the TV's remote_configure: the features both sides support, and who we are. */
    public static byte[] configure(long tvFeatures, String model, String vendor) {
        Proto.Writer info = new Proto.Writer()
                .string(1, model)
                .string(2, vendor)
                .varint(3, 1)
                .string(4, "1")
                .string(5, "atvremote")
                .string(6, "1.0.0");
        Proto.Writer cfg = new Proto.Writer().varint(1, FEATURES & tvFeatures).message(2, info);
        return new Proto.Writer().message(1, cfg).toByteArray();
    }

    public static byte[] setActive(long features) {
        return new Proto.Writer().message(2, new Proto.Writer().varint(1, features)).toByteArray();
    }

    public static byte[] pong(long val1) {
        return new Proto.Writer().message(9, new Proto.Writer().varint(1, val1)).toByteArray();
    }

    public static byte[] key(int keyCode) {
        Proto.Writer k = new Proto.Writer().varint(1, keyCode).varint(2, DIRECTION_SHORT);
        return new Proto.Writer().message(10, k).toByteArray();
    }
}
