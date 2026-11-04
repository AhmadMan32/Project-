package com.ahmadman.linkdownloader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny image loader for video thumbnails (memory cache, background download). */
final class Thumbnails {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MAX_BYTES = 6L * 1024 * 1024;

    private Thumbnails() {
    }

    static void load(ImageView view, String url, int maxWidth) {
        view.setTag(url);
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap b = fetch(url, maxWidth);
            if (b == null) {
                return;
            }
            CACHE.put(url, b);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(b);
                }
            });
        });
    }

    private static Bitmap fetch(String url, int maxWidth) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
            if (c.getResponseCode() != 200) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
            }
            byte[] data = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidth) {
                sample *= 2;
            }
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }
}
