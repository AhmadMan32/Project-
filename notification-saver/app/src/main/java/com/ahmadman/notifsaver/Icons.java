package com.ahmadman.notifsaver;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App icons, loaded off the main thread and cached. */
final class Icons {

    private static final LruCache<String, Drawable.ConstantState> CACHE = new LruCache<>(120);
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object NONE = new Object();
    private static final LruCache<String, Object> MISSING = new LruCache<>(200);

    private Icons() {
    }

    static void into(ImageView view, String pkg, String name) {
        view.setTag(R.id.icon, pkg);
        Drawable.ConstantState cached = pkg == null ? null : CACHE.get(pkg);
        if (cached != null) {
            view.setImageDrawable(cached.newDrawable(view.getResources()));
            return;
        }
        view.setImageDrawable(new Ui.Letter(view.getContext(), name));
        if (pkg == null || MISSING.get(pkg) != null) {
            return;
        }
        Context app = view.getContext().getApplicationContext();
        LOADER.execute(() -> {
            Drawable d;
            try {
                d = app.getPackageManager().getApplicationIcon(pkg);
            } catch (Exception e) {
                d = null;
            }
            final Drawable icon = d;
            if (icon == null || icon.getConstantState() == null) {
                MISSING.put(pkg, NONE);
                return;
            }
            CACHE.put(pkg, icon.getConstantState());
            MAIN.post(() -> {
                if (pkg.equals(view.getTag(R.id.icon))) {
                    view.setImageDrawable(icon);
                }
            });
        });
    }
}
