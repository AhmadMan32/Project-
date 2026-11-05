package com.ahmadman.kenarpanel.atv;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Just enough Protocol Buffers for the Android TV remote protocol: varints, strings, nested messages. */
public final class Proto {

    private Proto() {
    }

    /** Builds one message. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }

        private void tag(int field, int wire) {
            raw(((long) field << 3) | wire);
        }

        public Writer varint(int field, long value) {
            tag(field, 0);
            raw(value);
            return this;
        }

        public Writer bool(int field, boolean value) {
            return varint(field, value ? 1 : 0);
        }

        public Writer bytes(int field, byte[] value) {
            tag(field, 2);
            raw(value.length);
            out.write(value, 0, value.length);
            return this;
        }

        public Writer string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        public Writer message(int field, Writer nested) {
            return bytes(field, nested.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** One field of a parsed message. */
    public static final class Field {
        public final int number;
        public final long value;
        public final byte[] bytes;

        Field(int number, long value, byte[] bytes) {
            this.number = number;
            this.value = value;
            this.bytes = bytes;
        }

        public String string() {
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** Splits a message into fields; unknown wire types end the parse. */
    public static List<Field> parse(byte[] b) throws IOException {
        List<Field> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long key = varint(b, pos);
            int number = (int) (key >>> 3);
            int wire = (int) (key & 7);
            switch (wire) {
                case 0:
                    out.add(new Field(number, varint(b, pos), null));
                    break;
                case 2: {
                    long len = varint(b, pos);
                    if (len < 0 || pos[0] + len > b.length) {
                        throw new IOException("bad length");
                    }
                    byte[] v = new byte[(int) len];
                    System.arraycopy(b, pos[0], v, 0, v.length);
                    pos[0] += v.length;
                    out.add(new Field(number, 0, v));
                    break;
                }
                case 5:
                    pos[0] += 4;
                    break;
                case 1:
                    pos[0] += 8;
                    break;
                default:
                    throw new IOException("wire type " + wire);
            }
        }
        return out;
    }

    /** First field with this number, or null. */
    public static Field find(List<Field> fields, int number) {
        for (Field f : fields) {
            if (f.number == number) {
                return f;
            }
        }
        return null;
    }

    private static long varint(byte[] b, int[] pos) throws IOException {
        long r = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (pos[0] >= b.length) {
                throw new IOException("truncated varint");
            }
            int c = b[pos[0]++] & 0xFF;
            r |= (long) (c & 0x7F) << shift;
            if (c < 0x80) {
                return r;
            }
        }
        throw new IOException("varint too long");
    }

    /** Writes a message preceded by its length as a varint (how both channels frame messages). */
    public static void writeDelimited(OutputStream out, byte[] msg) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(msg.length + 3);
        long v = msg.length;
        while ((v & ~0x7FL) != 0) {
            b.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        b.write((int) v);
        b.write(msg, 0, msg.length);
        out.write(b.toByteArray()); // one write: one TLS record
        out.flush();
    }

    /** Reads one length-delimited message; null at the end of the stream. */
    public static byte[] readDelimited(InputStream in) throws IOException {
        long len = 0;
        for (int shift = 0; ; shift += 7) {
            int c = in.read();
            if (c < 0) {
                if (shift == 0) {
                    return null;
                }
                throw new EOFException();
            }
            len |= (long) (c & 0x7F) << shift;
            if (c < 0x80) {
                break;
            }
            if (shift > 28) {
                throw new IOException("bad length");
            }
        }
        if (len > 1 << 20) {
            throw new IOException("message too long: " + len);
        }
        byte[] msg = new byte[(int) len];
        int read = 0;
        while (read < msg.length) {
            int n = in.read(msg, read, msg.length - read);
            if (n < 0) {
                throw new EOFException();
            }
            read += n;
        }
        return msg;
    }
}
