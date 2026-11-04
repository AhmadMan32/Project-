package com.ahmadman.linkdownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The download list: queue, progress and history (kept across app restarts). */
public final class Downloads {

    public interface Listener {
        void onDownloadsChanged();
    }

    private static final int KEEP = 60;
    private static Downloads instance;

    public static synchronized Downloads get(Context c) {
        if (instance == null) {
            instance = new Downloads(c.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final List<Item> items = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = this::dispatch;
    private long lastSave;

    private Downloads(Context app) {
        this.app = app;
        load();
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("downloads", Context.MODE_PRIVATE);
    }

    private synchronized void load() {
        try {
            JSONArray a = new JSONArray(prefs().getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Item it = Item.fromJson(a.getJSONObject(i));
                if (Item.RUNNING.equals(it.status)) {
                    // the app was closed while downloading
                    it.status = Item.FAILED;
                    it.reason = "INTERRUPTED";
                }
                items.add(it);
            }
        } catch (JSONException e) {
            items.clear();
        }
    }

    private synchronized void save() {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            try {
                a.put(it.toJson());
            } catch (JSONException ignored) {
                // skip broken entry
            }
        }
        prefs().edit().putString("items", a.toString()).apply();
        lastSave = System.currentTimeMillis();
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void dispatch() {
        for (Listener l : listeners) {
            l.onDownloadsChanged();
        }
    }

    /** Coalesced UI refresh (progress callbacks arrive many times a second). */
    void changed(boolean persist) {
        if (persist || System.currentTimeMillis() - lastSave > 5000) {
            save();
        }
        main.removeCallbacks(notifyRunnable);
        main.postDelayed(notifyRunnable, 120);
    }

    public synchronized List<Item> snapshot() {
        return new ArrayList<>(items);
    }

    public synchronized Item find(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) {
                return it;
            }
        }
        return null;
    }

    public Item enqueue(Item it) {
        synchronized (this) {
            it.id = UUID.randomUUID().toString();
            it.created = System.currentTimeMillis();
            it.status = Item.QUEUED;
            items.add(0, it);
            while (items.size() > KEEP) {
                Item last = items.get(items.size() - 1);
                if (last.isActive()) {
                    break;
                }
                items.remove(items.size() - 1);
            }
        }
        changed(true);
        DownloadService.start(app);
        return it;
    }

    /** Oldest queued item, or null. */
    synchronized Item next() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (Item.QUEUED.equals(items.get(i).status)) {
                return items.get(i);
            }
        }
        return null;
    }

    public synchronized boolean hasQueued() {
        return next() != null;
    }

    public void cancel(String id) {
        Item it = find(id);
        if (it == null) {
            return;
        }
        if (Item.RUNNING.equals(it.status)) {
            it.cancelRequested = true;
            Engine.cancel(id); // the service marks it cancelled when the process ends
        }
        synchronized (this) {
            if (Item.QUEUED.equals(it.status)) {
                it.status = Item.CANCELLED;
            }
        }
        changed(true);
    }

    public void retry(String id) {
        Item it = find(id);
        if (it == null || it.isActive()) {
            return;
        }
        synchronized (this) {
            it.status = Item.QUEUED;
            it.cancelRequested = false;
            it.percent = 0;
            it.detail = "";
            it.reason = null;
            it.error = null;
            items.remove(it);
            items.add(0, it);
        }
        changed(true);
        DownloadService.start(app);
    }

    public void remove(String id) {
        Item it = find(id);
        if (it == null) {
            return;
        }
        if (it.isActive()) {
            cancel(id);
        }
        synchronized (this) {
            items.remove(it);
        }
        changed(true);
    }

    public void clearFinished() {
        synchronized (this) {
            List<Item> keep = new ArrayList<>();
            for (Item it : items) {
                if (it.isActive()) {
                    keep.add(it);
                }
            }
            items.clear();
            items.addAll(keep);
        }
        changed(true);
    }
}
