package com.ahmadman.notifsaver;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.ahmadman.notifsaver.core.Capture;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Writes captures in the background (in arrival order) and tells open screens immediately. */
final class Store {

    interface Listener {
        void onSaved();
    }

    private static volatile Store instance;

    static Store get(Context c) {
        Store s = instance;
        if (s == null) {
            synchronized (Store.class) {
                if (instance == null) {
                    instance = new Store(c.getApplicationContext());
                }
                s = instance;
            }
        }
        return s;
    }

    /** Closes the database and forgets the instance (tests). */
    static void reset() {
        Store s = instance;
        instance = null;
        if (s != null) {
            s.writer.shutdown();
            s.reader.shutdown();
            s.db.close();
        }
    }

    final Db db;
    final ExecutorService reader = Executors.newFixedThreadPool(2);
    private final Context app;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "notif-writer"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Runnable dispatch = () -> {
        for (Listener l : listeners) {
            l.onSaved();
        }
    };
    private int sinceCleanup;
    private volatile long lastSavedAt;

    private Store(Context app) {
        this.app = app;
        this.db = new Db(app);
    }

    void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    long lastSavedAt() {
        return lastSavedAt;
    }

    /** App name for a package (cached); the package name if it cannot be resolved. */
    synchronized String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) {
            return l;
        }
        PackageManager pm = app.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            l = pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            l = pkg;
        }
        labels.put(pkg, l);
        return l;
    }

    void save(List<Capture> captures) {
        if (captures.isEmpty()) {
            return;
        }
        writer.execute(() -> {
            boolean any = false;
            for (Capture c : captures) {
                try {
                    if (db.insert(c) > 0) {
                        any = true;
                    }
                } catch (RuntimeException e) {
                    android.util.Log.e("NotifSaver", "insert failed", e);
                }
            }
            if (any) {
                lastSavedAt = System.currentTimeMillis();
                main.removeCallbacks(dispatch);
                main.postDelayed(dispatch, 150);
                KeepAliveService.refresh(app);
                if (++sinceCleanup >= 200) {
                    sinceCleanup = 0;
                    cleanupNow();
                }
            }
        });
    }

    /** Applies the retention setting (0 = keep forever). */
    void cleanup() {
        writer.execute(this::cleanupNow);
    }

    private void cleanupNow() {
        int days = Prefs.get(app).retentionDays;
        if (days > 0) {
            db.deleteOlderThan(System.currentTimeMillis() - days * 86_400_000L);
        }
    }

    /** Runs a write (delete ...) on the writer thread, then refreshes the screens. */
    void write(Runnable r) {
        writer.execute(() -> {
            r.run();
            main.post(dispatch);
        });
    }

    /** Waits until queued writes are done (tests). */
    void flush() throws InterruptedException {
        final Object lock = new Object();
        final boolean[] done = {false};
        writer.execute(() -> {
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 5000;
            while (!done[0] && System.currentTimeMillis() < until) {
                lock.wait(100);
            }
        }
    }
}
