package com.ahmadman.kenarpanel;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;

/**
 * A small animation driven straight by display frames (one step per refresh: 90 steps a second
 * on a 90 Hz screen). It starts on the very next frame, without waiting for a layout pass.
 */
final class Tween implements Choreographer.FrameCallback {

    interface Update {
        void value(float v);
    }

    private final float from;
    private final float to;
    private final float duration;
    private final TimeInterpolator curve;
    private final Update update;
    private final Runnable end;
    private long start = -1;
    private boolean running;

    private Tween(float from, float to, long millis, TimeInterpolator curve, Update update, Runnable end) {
        this.from = from;
        this.to = to;
        this.duration = millis;
        this.curve = curve;
        this.update = update;
        this.end = end;
    }

    /** Honours the phone's animator duration scale (0 = animations off). */
    static float systemScale() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ValueAnimator.getDurationScale();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled() ? 1f : 0f;
        }
        return 1f;
    }

    /** Starts at once; with no time to animate it jumps to the end value. */
    static Tween run(float from, float to, long millis, TimeInterpolator curve, Update update, Runnable end) {
        long ms = Math.round(millis * systemScale());
        Tween t = new Tween(from, to, ms, curve, update, end);
        if (ms <= 0) {
            update.value(to);
            if (end != null) {
                end.run();
            }
            return t;
        }
        t.running = true;
        t.start = SystemClock.uptimeMillis();
        Choreographer.getInstance().postFrameCallback(t);
        return t;
    }

    boolean running() {
        return running;
    }

    void cancel() {
        if (running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        float f = Math.min(1f, (SystemClock.uptimeMillis() - start) / duration);
        float c = curve == null ? f : curve.getInterpolation(f);
        update.value(from + (to - from) * c);
        if (f >= 1f) {
            running = false;
            update.value(to);
            if (end != null) {
                end.run();
            }
        } else {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
