package com.ahmadman.podpil;

import android.os.Handler;
import android.os.Looper;

import com.ahmadman.podpil.core.Estimator;
import com.ahmadman.podpil.core.Status;

import java.util.concurrent.CopyOnWriteArrayList;

/** What the screens show: the latest merged status and the connection, shared in the process. */
final class PodState {

    interface Listener {
        void onPodsChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable DISPATCH = () -> {
        for (Listener l : LISTENERS) {
            l.onPodsChanged();
        }
    };

    /** Latest status (null = nothing seen since the service started). */
    static volatile Status status;
    static volatile Estimator.Estimate estimate;
    /** Name of the connected Apple headphones, null when none is connected. */
    static volatile String connectedName;
    /** Any Bluetooth audio device connected (scanning runs). */
    static volatile boolean audioConnected;
    static volatile boolean aapLive;
    static volatile boolean bluetoothOn = true;

    private PodState() {
    }

    static void add(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    static void changed() {
        MAIN.removeCallbacks(DISPATCH);
        MAIN.post(DISPATCH);
    }

    /** Status is recent enough to call "live". */
    static boolean live(long now) {
        Status s = status;
        return s != null && now - s.time < 30_000;
    }

    static void reset() {
        status = null;
        estimate = null;
        connectedName = null;
        audioConnected = false;
        aapLive = false;
        bluetoothOn = true;
    }
}
