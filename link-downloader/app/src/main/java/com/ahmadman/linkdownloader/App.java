package com.ahmadman.linkdownloader;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Prepares the download engine in the background as soon as the app starts. */
public class App extends Application {

    public interface EngineListener {
        void onEngineState();
    }

    static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArrayList<EngineListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static volatile boolean engineReady;
    static volatile boolean updating;
    static volatile String engineError;
    static volatile String engineVersion = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Downloads.get(this); // marks interrupted downloads
        BACKGROUND.execute(() -> {
            try {
                Engine.ensureReady(this);
                engineReady = true;
                engineVersion = Engine.version(this);
                notifyEngine();
                boolean auto = Engine.prefs(this).getBoolean("auto_update", true);
                if (auto && Engine.updateDue(this) && !DownloadService.isRunning()) {
                    update();
                }
            } catch (Throwable t) {
                engineError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                notifyEngine();
            }
        });
    }

    /** Updates yt-dlp; call on {@link #BACKGROUND}. Returns null on success, else the error. */
    String update() {
        updating = true;
        notifyEngine();
        try {
            Engine.update(this);
            engineVersion = Engine.version(this);
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        } finally {
            updating = false;
            notifyEngine();
        }
    }

    static void addListener(EngineListener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(EngineListener l) {
        LISTENERS.remove(l);
    }

    static void notifyEngine() {
        MAIN.post(() -> {
            for (EngineListener l : LISTENERS) {
                l.onEngineState();
            }
        });
    }
}
