package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Bitmap decoding (sampled, EXIF aware), encoding and simple effects. */
public final class Images {

    private Images() {
    }

    public static Bitmap.CompressFormat format(String ext) {
        switch (ext) {
            case "png":
                return Bitmap.CompressFormat.PNG;
            case "webp":
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? Bitmap.CompressFormat.WEBP_LOSSY : webpLegacy();
            default:
                return Bitmap.CompressFormat.JPEG;
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap.CompressFormat webpLegacy() {
        return Bitmap.CompressFormat.WEBP;
    }

    public static byte[] encode(Bitmap bmp, String ext, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Bitmap src = bmp;
        if (!ext.equals("png") && bmp.hasAlpha()) {
            src = flatten(bmp); // JPEG has no transparency: put it on white
        }
        src.compress(format(ext), quality, bos);
        if (src != bmp) {
            src.recycle();
        }
        return bos.toByteArray();
    }

    public static void save(Bitmap bmp, String ext, int quality, File f) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(encode(bmp, ext, quality));
        }
    }

    public static Bitmap flatten(Bitmap bmp) {
        Bitmap out = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
        out.eraseColor(Color.WHITE);
        new Canvas(out).drawBitmap(bmp, 0, 0, null);
        return out;
    }

    public static Bitmap grayscale(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        out.eraseColor(Color.WHITE);
        new Canvas(out).drawBitmap(src, 0, 0, p);
        return out;
    }

    /** Image size without decoding pixels; {0,0} if not an image. */
    public static int[] bounds(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return new int[]{Math.max(0, o.outWidth), Math.max(0, o.outHeight)};
    }

    /** Decodes so that the longer side is at most {@code maxSide}, applying the EXIF rotation. */
    public static Bitmap decode(byte[] data, int maxSide) {
        int[] b = bounds(data);
        if (b[0] <= 0 || b[1] <= 0) {
            return null;
        }
        int sample = 1;
        while (Math.max(b[0], b[1]) / (sample * 2) >= maxSide) {
            sample *= 2;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError e) {
            o.inSampleSize = sample * 2;
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        }
        if (bmp == null) {
            return null;
        }
        if (Math.max(bmp.getWidth(), bmp.getHeight()) > maxSide) {
            float s = maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight());
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * s)),
                    Math.max(1, Math.round(bmp.getHeight() * s)), true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            bmp = scaled;
        }
        int rotation = exifRotation(data);
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) {
                bmp.recycle();
            }
            bmp = r;
        }
        return bmp;
    }

    /** Clockwise rotation in degrees stored in the JPEG EXIF data (0 when unknown). */
    public static int exifRotation(byte[] data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || data.length < 4
                || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return 0;
        }
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    public static boolean isJpeg(byte[] data) {
        return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
    }
}
