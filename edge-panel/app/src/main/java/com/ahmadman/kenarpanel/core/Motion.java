package com.ahmadman.kenarpanel.core;

/** Animation math: spring curve, cascade timing, rubber band and display refresh rate. */
public final class Motion {

    private Motion() {
    }

    /** Animation speed setting. */
    public enum Speed {
        FAST(170), MEDIUM(280), SLOW(450), OFF(0);

        /** Length of the open/close movement in milliseconds. */
        public final int millis;

        Speed(int millis) {
            this.millis = millis;
        }

        public static Speed of(String name) {
            try {
                return valueOf(name);
            } catch (IllegalArgumentException | NullPointerException e) {
                return FAST;
            }
        }
    }

    /**
     * Under-damped spring from 0 to 1: overshoots about 3 % and settles by t = 1
     * (damping ratio 0.75, natural frequency 8 per animation length).
     */
    public static float spring(float t) {
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        double zeta = 0.75;
        double w = 8;
        double wd = w * Math.sqrt(1 - zeta * zeta);
        double e = Math.exp(-zeta * w * t);
        return (float) (1 - e * (Math.cos(wd * t) + zeta * w / wd * Math.sin(wd * t)));
    }

    /**
     * Cascade: child {@code index} of {@code count} fades in a little after the one before,
     * driven by the panel's open progress (0..1).
     */
    public static float cascade(float progress, int index, int count) {
        if (count <= 1) {
            return clamp(progress);
        }
        float step = Math.min(0.04f, 0.4f / (count - 1));
        float span = 1f - step * (count - 1);
        return clamp((progress - index * step) / span);
    }

    /** Pulling past fully open gets harder, like a rubber band. */
    public static float rubberBand(float progress) {
        return progress <= 1f ? Math.max(0f, progress) : 1f + (progress - 1f) * 0.15f;
    }

    /**
     * Should a pull that ends here open the panel? Yes past half way or on a quick inward flick,
     * no on a quick outward flick.
     *
     * @param velocity inward speed in dp per second (negative = outward)
     */
    public static boolean settleOpen(float progress, float velocity) {
        if (velocity > 600) {
            return true;
        }
        if (velocity < -600) {
            return false;
        }
        return progress >= 0.5f;
    }

    /**
     * Smallest supported refresh rate of at least {@code wanted} Hz (90 Hz panels: 90; 120 Hz
     * panels without a 90 Hz mode: 120); 0 when the screen cannot go that fast.
     */
    public static float refreshRate(float[] supported, float wanted) {
        float best = 0;
        if (supported == null) {
            return 0;
        }
        for (float r : supported) {
            if (r + 0.5f >= wanted && (best == 0 || r < best)) {
                best = r;
            }
        }
        return best;
    }

    public static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
