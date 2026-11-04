package com.ahmadman.pdfconverter.convert;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Access to the files the user picked (content:// URIs). */
public final class Sources {

    /** Text inputs larger than this are refused (they would not fit in a PDF sensibly). */
    private static final long MAX_TEXT = 32L * 1024 * 1024;

    private Sources() {
    }

    public static String displayName(Context c, Uri uri) {
        String name = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cur = c.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    name = cur.getString(0);
                }
            } catch (RuntimeException ignored) {
                // some providers do not support queries
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name == null || name.isEmpty() ? "dosya" : name;
    }

    public static long size(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) {
                return cur.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // unknown
        }
        return -1;
    }

    /** MIME type from the provider, falling back to the file extension. */
    public static String mime(Context c, Uri uri) {
        String type = null;
        try {
            type = c.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
            // unknown
        }
        if (type == null || type.equals("application/octet-stream")) {
            String ext = extension(displayName(c, uri));
            String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (guess != null) {
                type = guess;
            } else if (ext.equals("md") || ext.equals("markdown")) {
                type = "text/markdown";
            }
        }
        return type == null ? "application/octet-stream" : type;
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (s.length() > 120) {
            String ext = extension(s);
            s = s.substring(0, 110) + (ext.isEmpty() ? "" : "." + ext);
        }
        return s.isEmpty() ? "dosya" : s;
    }

    public static InputStream open(Context c, Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("cannot open " + uri);
        }
        return in;
    }

    /** Copies the input into the work folder (PdfRenderer and PDFBox need a seekable file). */
    public static File copyToFile(Context c, Uri uri, File dir, String name) throws IOException {
        File f = new File(dir, safeName(name));
        try (InputStream in = open(c, uri); OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        return f;
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    public static byte[] readBytes(Context c, Uri uri, long max) throws IOException {
        try (InputStream in = open(c, uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    throw new IOException("file too large");
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Reads text, detecting UTF-8 / UTF-16 BOMs and falling back to Windows-1254 (Turkish). */
    public static String readText(Context c, Uri uri) throws IOException {
        byte[] data = readBytes(c, uri, MAX_TEXT);
        return decode(data);
    }

    static String decode(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        if (isValidUtf8(data)) {
            return new String(data, StandardCharsets.UTF_8);
        }
        try {
            return new String(data, Charset.forName("windows-1254"));
        } catch (RuntimeException e) {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean isValidUtf8(byte[] d) {
        int i = 0;
        while (i < d.length) {
            int b = d[i] & 0xFF;
            int extra;
            if (b < 0x80) {
                extra = 0;
            } else if (b >= 0xC2 && b <= 0xDF) {
                extra = 1;
            } else if (b >= 0xE0 && b <= 0xEF) {
                extra = 2;
            } else if (b >= 0xF0 && b <= 0xF4) {
                extra = 3;
            } else {
                return false;
            }
            for (int k = 1; k <= extra; k++) {
                if (i + k >= d.length || (d[i + k] & 0xC0) != 0x80) {
                    return false;
                }
            }
            i += extra + 1;
        }
        return true;
    }

    public static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
