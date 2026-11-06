package com.ahmadman.kenarpanel;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.ConsumerIrManager;

import com.ahmadman.kenarpanel.core.Ir;
import com.ahmadman.kenarpanel.core.Remotes;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends infrared codes (LG TV, Tivibu box) through the phone's infrared emitter. Sending runs on its own thread, so
 * a key press never waits and never stalls an animation.
 */
final class Remote {

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
    private int[][] ranges;

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

    /** Sends the key at once (on finger down). */
    boolean press(Remotes.Key key) {
        return send(key.frame());
    }

    /** Sends one infrared frame on the sending thread (in order, never blocking the caller). */
    boolean send(Ir.Frame f) {
        if (!available()) {
            return false;
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
        return true;
    }

    void shutdown() {
        sender.shutdown();
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
}
