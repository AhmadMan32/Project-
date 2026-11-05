package com.ahmadman.kenarpanel;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.ahmadman.kenarpanel.atv.Session;
import com.ahmadman.kenarpanel.atv.Tls;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps the connection to the paired Android TV. It connects by itself, without asking, as
 * soon as the panel starts to come out (or the Android TV page is chosen), finds the TV again
 * if its address changed, and lets go a while after the panel closes.
 */
final class AtvRemote implements Session.Listener {

    enum State { NOT_PAIRED, CONNECTING, READY, OFFLINE, REFUSED }

    interface Listener {
        void onAtvState();
    }

    /** Tests: receives key codes instead of the network. */
    interface Sink {
        boolean key(int code);
    }

    private static final long IDLE_MS = 120_000;
    private static final long PENDING_MS = 4_000;
    private static AtvRemote instance;

    static synchronized AtvRemote get(Context c) {
        if (instance == null) {
            instance = new AtvRemote(c.getApplicationContext());
        }
        return instance;
    }

    /** Tests. */
    static synchronized void reset() {
        if (instance != null) {
            instance.close();
        }
        instance = null;
    }

    Sink testSink;
    private final Context app;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "atv"));
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Session session;
    private State state;
    private int pendingKey = -1;
    private long pendingAt;
    private boolean wanted;
    private int attempts;
    private AtvFinder finder;
    private final Runnable idle = this::close;
    private final Runnable retry = this::ensure;

    private AtvRemote(Context app) {
        this.app = app;
        this.prefs = new Prefs(app);
        state = prefs.atvPaired() ? State.OFFLINE : State.NOT_PAIRED;
    }

    void add(Listener l) {
        listeners.addIfAbsent(l);
    }

    void remove(Listener l) {
        listeners.remove(l);
    }

    State state() {
        return state;
    }

    String name() {
        String n = prefs.atvName();
        return n != null ? n : "Android TV";
    }

    private void set(State s) {
        if (state != s) {
            state = s;
            for (Listener l : listeners) {
                l.onAtvState();
            }
        }
    }

    /** Connects now if not connected; stays connected until {@link #idleLater()} times out. */
    void ensure() {
        main.removeCallbacks(idle);
        main.removeCallbacks(retry);
        wanted = true;
        if (testSink != null) {
            set(State.READY);
            return;
        }
        if (!prefs.atvPaired()) {
            set(State.NOT_PAIRED);
            return;
        }
        if (session != null && !session.closed()) {
            return; // connecting or connected
        }
        set(State.CONNECTING);
        String host = prefs.atvHost();
        int port = prefs.atvPort();
        String pin = prefs.atvPin();
        worker.execute(() -> {
            Tls.Identity id;
            try {
                id = AtvIdentity.get(app);
            } catch (Exception e) {
                main.post(() -> set(State.OFFLINE));
                return;
            }
            Session s = new Session(id, host, port, pin, Build.MODEL, Build.MANUFACTURER, this);
            main.post(() -> {
                if (!wanted || (session != null && !session.closed())) {
                    return;
                }
                session = s;
                s.start();
            });
        });
    }

    /** The panel closed: disconnect after a while unless it is used again. */
    void idleLater() {
        wanted = false;
        main.removeCallbacks(idle);
        main.postDelayed(idle, IDLE_MS);
    }

    void close() {
        wanted = false;
        main.removeCallbacks(idle);
        main.removeCallbacks(retry);
        stopFinder();
        if (session != null) {
            session.close();
            session = null;
        }
        if (state == State.READY || state == State.CONNECTING) {
            set(prefs.atvPaired() ? State.OFFLINE : State.NOT_PAIRED);
        }
    }

    /** New pairing saved (or forgotten). */
    void pairedChanged() {
        close();
        attempts = 0;
        set(prefs.atvPaired() ? State.OFFLINE : State.NOT_PAIRED);
    }

    /** Sends at once when connected; otherwise connects and sends it when ready (within 4 s). */
    boolean key(int code) {
        if (testSink != null) {
            return testSink.key(code);
        }
        Session s = session;
        if (s != null && s.ready()) {
            return s.key(code);
        }
        pendingKey = code;
        pendingAt = SystemClock.uptimeMillis();
        ensure();
        return false;
    }

    @Override
    public void onReady(Session s) {
        main.post(() -> {
            if (s != session) {
                return;
            }
            attempts = 0;
            stopFinder();
            set(State.READY);
            if (pendingKey >= 0 && SystemClock.uptimeMillis() - pendingAt < PENDING_MS) {
                s.key(pendingKey);
            }
            pendingKey = -1;
        });
    }

    @Override
    public void onClosed(Session s, boolean needsPairing) {
        main.post(() -> {
            if (s != session) {
                return;
            }
            session = null;
            if (!wanted) {
                set(prefs.atvPaired() ? State.OFFLINE : State.NOT_PAIRED);
                return;
            }
            set(needsPairing ? State.REFUSED : State.OFFLINE);
            attempts++;
            if (!needsPairing && attempts >= 1) {
                findAgain(); // the TV may have a new address on the Wi-Fi
            }
            main.postDelayed(retry, Math.min(15_000, 1500L * attempts));
        });
    }

    /** Looks for the paired TV by name and saves its new address. */
    private void findAgain() {
        if (finder != null) {
            return;
        }
        String name = prefs.atvName();
        finder = new AtvFinder(app, (n, host, port) -> {
            if (name == null || name.equals(n)) {
                if (!host.equals(prefs.atvHost()) || port != prefs.atvPort()) {
                    prefs.atvHost(host, port);
                }
                stopFinder();
                if (wanted) {
                    ensure();
                }
            }
        });
        finder.start();
        main.postDelayed(this::stopFinder, 15_000);
    }

    private void stopFinder() {
        if (finder != null) {
            finder.stop();
            finder = null;
        }
    }
}
