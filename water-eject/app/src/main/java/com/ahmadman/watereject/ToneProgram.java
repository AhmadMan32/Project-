package com.ahmadman.watereject;

/**
 * Pure sample generator for the water-ejection sound (no Android dependencies, unit tested).
 *
 * Automatic program (total duration T):
 * <ol>
 *   <li>0 - 35 %: steady 165 Hz tone. Low frequencies move the speaker membrane the
 *       furthest, pushing water out of the grille.</li>
 *   <li>35 - 65 %: the same tone in short bursts; the start/stop "kicks" shake droplets loose.</li>
 *   <li>65 - 100 %: a sweep between 120 and 350 Hz so the resonance of any grille size is hit.</li>
 * </ol>
 * Manual mode plays a user-chosen frequency (sine or soft square).
 */
final class ToneProgram {

    static final int RATE = 48000;

    static final double STEADY_END = 0.35;
    static final double PULSE_END = 0.65;
    static final double FADE_IN_S = 0.15;
    static final double SWEEP_PERIOD_S = 5.0;
    static final double PULSE_PERIOD_S = 0.40;
    static final double PULSE_ON_S = 0.25;
    static final double PULSE_RAMP_S = 0.02;
    static final double LEVEL = 0.95;
    /** Per-sample smoothing of manual frequency changes (about 20 ms). */
    private static final double GLIDE = 0.001;
    /** Per-sample amplitude smoothing (about 5 ms): no jumps between program segments. */
    private static final double AMP_SMOOTH = 1.0 / 240;

    private final int mode;
    private final long totalFrames;

    private volatile float manualFreq;
    private volatile int manualWave;

    private double phase;
    private double glideFreq;
    private double lastGain;
    private double smoothAmp = 1.0;
    private long frame;
    private int wave = ToneEngine.WAVE_SINE;

    private volatile float currentFreq;
    private volatile float currentAmp;
    private volatile int currentPhase;

    ToneProgram(int mode, int durationSeconds, float freq, int wave) {
        this.mode = mode;
        int seconds = mode == ToneEngine.MODE_AUTO ? durationSeconds : ToneEngine.MANUAL_MAX_SECONDS;
        this.totalFrames = (long) seconds * RATE;
        this.manualFreq = clampFreq(freq);
        this.manualWave = wave;
        this.glideFreq = mode == ToneEngine.MODE_AUTO ? ToneEngine.BASE_FREQ : manualFreq;
        this.currentFreq = (float) glideFreq;
        this.currentPhase = mode == ToneEngine.MODE_AUTO ? ToneEngine.PHASE_STEADY : ToneEngine.PHASE_MANUAL;
    }

    static float clampFreq(float f) {
        return Math.max(ToneEngine.MIN_FREQ, Math.min(ToneEngine.MAX_FREQ, f));
    }

    void setManualFrequency(float f) {
        manualFreq = clampFreq(f);
    }

    void setManualWave(int w) {
        manualWave = w;
    }

    long getTotalFrames() {
        return totalFrames;
    }

    long getFramesDone() {
        return Math.min(frame, totalFrames);
    }

    boolean isFinished() {
        return frame >= totalFrames;
    }

    float getCurrentFreq() {
        return currentFreq;
    }

    float getCurrentAmp() {
        return currentAmp;
    }

    int getCurrentPhase() {
        return currentPhase;
    }

    /** Fills the whole buffer with the next samples of the program. */
    void fill(short[] buf) {
        wave = mode == ToneEngine.MODE_MANUAL ? manualWave : ToneEngine.WAVE_SINE;
        double target = manualFreq;
        double total = totalFrames;
        double f = glideFreq;
        int ph = currentPhase;
        for (int i = 0; i < buf.length; i++, frame++) {
            double t = frame / (double) RATE;
            double amp = 1.0;
            if (mode == ToneEngine.MODE_MANUAL) {
                glideFreq += (target - glideFreq) * GLIDE;
                f = glideFreq;
                ph = ToneEngine.PHASE_MANUAL;
            } else {
                double p = frame / total;
                if (p < STEADY_END) {
                    f = ToneEngine.BASE_FREQ;
                    ph = ToneEngine.PHASE_STEADY;
                } else if (p < PULSE_END) {
                    f = ToneEngine.BASE_FREQ;
                    // Start on the plateau of a burst so the level does not drop abruptly.
                    amp = pulseEnvelope(t - STEADY_END * total / RATE + PULSE_RAMP_S);
                    ph = ToneEngine.PHASE_PULSE;
                } else {
                    double sweepT = t - PULSE_END * total / RATE;
                    double x = (sweepT % SWEEP_PERIOD_S) / SWEEP_PERIOD_S;
                    double tri = x < 0.5 ? x * 2 : 2 - x * 2;
                    f = ToneEngine.SWEEP_LOW + (ToneEngine.SWEEP_HIGH - ToneEngine.SWEEP_LOW) * tri;
                    ph = ToneEngine.PHASE_SWEEP;
                }
            }
            smoothAmp += (amp - smoothAmp) * AMP_SMOOTH;
            lastGain = smoothAmp * Math.min(1.0, t / FADE_IN_S);
            advance(f);
            buf[i] = sample(lastGain);
        }
        currentFreq = (float) f;
        currentAmp = (float) lastGain;
        currentPhase = ph;
    }

    /** Ramps the current tone down to silence (prevents a "click" when stopping). */
    void fillFadeOut(short[] buf) {
        double f = currentFreq;
        double start = lastGain;
        for (int i = 0; i < buf.length; i++) {
            lastGain = start * (1.0 - (i + 1) / (double) buf.length);
            advance(f);
            buf[i] = sample(lastGain);
        }
        currentAmp = 0f;
    }

    private void advance(double f) {
        phase += 2 * Math.PI * f / RATE;
        if (phase > 2 * Math.PI) {
            phase -= 2 * Math.PI;
        }
    }

    private short sample(double gain) {
        double s = Math.sin(phase);
        if (wave == ToneEngine.WAVE_SQUARE) {
            // Soft square: more membrane travel than a sine, without harsh aliasing.
            s = Math.tanh(3.0 * s) / Math.tanh(3.0);
        }
        return (short) Math.round(s * gain * LEVEL * Short.MAX_VALUE);
    }

    /** Bursts: 0.25 s on (20 ms ramps), 0.15 s off, starting with a burst. */
    static double pulseEnvelope(double t) {
        double x = t % PULSE_PERIOD_S;
        if (x >= PULSE_ON_S) {
            return 0.0;
        }
        double up = Math.min(1.0, x / PULSE_RAMP_S);
        double down = Math.min(1.0, (PULSE_ON_S - x) / PULSE_RAMP_S);
        return Math.min(up, down);
    }
}
