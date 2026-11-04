package com.ahmadman.linkdownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Moves finished files into Movies/Link İndirici and Music/Link İndirici (visible in Gallery/Music). */
final class MediaSaver {

    static final String FOLDER = "Link İndirici";

    private MediaSaver() {
    }

    static boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (n.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (n.endsWith(".opus") || n.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (n.endsWith(".webm")) {
            return "video/webm";
        }
        if (n.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        return "video/mp4";
    }

    /** @return content:// URI of the saved file */
    @SuppressWarnings("deprecation")
    static Uri save(Context c, File file, boolean video) throws IOException {
        String name = file.getName();
        String mime = mime(name);
        boolean isAudio = mime.startsWith("audio/");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = c.getContentResolver();
            Uri collection = isAudio
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            if (isAudio) {
                v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            }
            Uri uri = cr.insert(collection, v);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            try (InputStream in = new FileInputStream(file); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("cannot write " + uri);
                }
                copy(in, out);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File target = unique(dir, name);
        try (InputStream in = new FileInputStream(file); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        final Uri[] result = new Uri[1];
        CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(c, new String[]{target.getAbsolutePath()}, new String[]{mime},
                (path, uri) -> {
                    result[0] = uri;
                    latch.countDown();
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (result[0] == null) {
            throw new IOException("media scan failed for " + target);
        }
        return result[0];
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int i = 2;
        while (f.exists()) {
            f = new File(dir, stem + " (" + i++ + ")" + ext);
        }
        return f;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /** "1080p" for videos, "320 kbps" for audio, read from the file itself. */
    static String quality(File f, boolean video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            if (video) {
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                if (w != null && h != null) {
                    int shortSide = Math.min(Integer.parseInt(w), Integer.parseInt(h));
                    return shortSide + "p";
                }
            } else {
                String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                if (br != null) {
                    return Math.round(Integer.parseInt(br) / 1000f) + " kbps";
                }
            }
        } catch (RuntimeException e) {
            // unknown
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
                // nothing
            }
        }
        return null;
    }
}
