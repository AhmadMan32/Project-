package com.ahmadman.kenarpanel;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.os.Handler;
import android.os.Looper;

import com.ahmadman.kenarpanel.core.Ir;
import com.ahmadman.kenarpanel.core.Remotes;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends TV remote codes through the phone's infrared emitter. Sending happens on its own
 * thread, so a button press never waits; held keys repeat.
 */
final class Remote {

    private static final long REPEAT_DELAY_MS = 350;
    private static final long REPEAT_EVERY_MS = 120;

    /** The infrared hardware; replaceable in tests. */
    interface Emitter {
        boolean available();

        int[][] ranges();

        void transmit(int frequency, int[] pattern);
    }

    /** Set by tests instead of the phone's emitter. */
    static Emitter testEmitter;

    private final Emitter ir;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ir");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private int[][] ranges;
    private boolean toggle;
    private Remotes.Key held;
    private Remotes.Brand heldBrand;

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (held != null) {
                transmit(heldBrand, held);
                main.postDelayed(this, REPEAT_EVERY_MS);
            }
        }
    };

    Remote(Context c) {
        ir = testEmitter != null ? testEmitter : new Phone(c);
    }

    boolean available() {
        try {
            return ir.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** ConsumerIrManager, the standard Android infrared API (Xiaomi, Huawei, older Samsung ...). */
    private static final class Phone implements Emitter {
        private final ConsumerIrManager m;

        Phone(Context c) {
            m = (ConsumerIrManager) c.getApplicationContext().getSystemService(Context.CONSUMER_IR_SERVICE);
        }

        @Override
        public boolean available() {
            return m != null && m.hasIrEmitter();
        }

        @Override
        public int[][] ranges() {
            ConsumerIrManager.CarrierFrequencyRange[] r = m.getCarrierFrequencies();
            int[][] rs = new int[r == null ? 0 : r.length][];
            for (int i = 0; i < rs.length; i++) {
                rs[i] = new int[]{r[i].getMinFrequency(), r[i].getMaxFrequency()};
            }
            return rs;
        }

        @SuppressLint("MissingPermission") // TRANSMIT_IR is a normal permission granted at install
        @Override
        public void transmit(int frequency, int[] pattern) {
            m.transmit(frequency, pattern);
        }
    }

    /** Finger down on a key: sends at once, then repeats while held for volume/channel/arrows. */
    boolean press(Remotes.Brand brand, Remotes.Key key) {
        if (!available() || !brand.has(key)) {
            return false;
        }
        release();
        toggle = !toggle; // RC5: a new press flips the toggle bit
        transmit(brand, key);
        if (key.repeats()) {
            held = key;
            heldBrand = brand;
            main.postDelayed(repeat, REPEAT_DELAY_MS);
        }
        return true;
    }

    void release() {
        held = null;
        main.removeCallbacks(repeat);
    }

    private void transmit(Remotes.Brand brand, Remotes.Key key) {
        Ir.Frame f = brand.frame(key, toggle);
        if (f == null) {
            return;
        }
        sender.execute(() -> {
            try {
                if (ranges == null) {
                    ranges = ir.ranges();
                }
                ir.transmit(Ir.bestFrequency(f.frequency, ranges), f.pattern);
            } catch (RuntimeException e) {
                android.util.Log.w("KenarPanel", "IR transmit failed", e);
            }
        });
    }

    void shutdown() {
        release();
        sender.shutdown();
    }
}
