package com.ahmadman.watereject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToneProgramTest {

    private static final int RATE = ToneProgram.RATE;
    private static final int BLOCK = 960;
    private static final int PEAK = (int) Math.round(ToneProgram.LEVEL * Short.MAX_VALUE);
    /** Largest legal sample-to-sample step: a full-level sine at the highest frequency used. */
    private static final double MAX_STEP_350 = PEAK * 2 * Math.PI * 350 / RATE * 1.1;

    private static short[] render(ToneProgram p, double seconds) {
        int blocks = (int) Math.ceil(seconds * RATE / BLOCK);
        short[] out = new short[blocks * BLOCK];
        short[] buf = new short[BLOCK];
        for (int b = 0; b < blocks; b++) {
            p.fill(buf);
            System.arraycopy(buf, 0, out, b * BLOCK, BLOCK);
        }
        return out;
    }

    /** Frequency from rising zero crossings in [from, to) seconds. */
    private static double frequency(short[] s, double from, double to) {
        int a = (int) (from * RATE);
        int b = (int) (to * RATE);
        int first = -1;
        int last = -1;
        int count = 0;
        for (int i = Math.max(1, a); i < b; i++) {
            if (s[i - 1] < 0 && s[i] >= 0) {
                if (first < 0) {
                    first = i;
                } else {
                    count++;
                }
                last = i;
            }
        }
        return count == 0 ? 0 : count / ((last - first) / (double) RATE);
    }

    private static double rms(short[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) s[i] * s[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    private static double maxStep(short[] s) {
        double max = 0;
        for (int i = 1; i < s.length; i++) {
            max = Math.max(max, Math.abs(s[i] - s[i - 1]));
        }
        return max;
    }

    @Test
    public void steadySegmentIs165Hz() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] s = render(p, 10);
        assertEquals(165.0, frequency(s, 1, 10), 0.5);
        assertEquals(ToneEngine.PHASE_STEADY, p.getCurrentPhase());
    }

    @Test
    public void startsSilentAndFadesIn() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] s = render(p, 1);
        assertTrue(Math.abs(s[0]) < 100);
        assertTrue(rms(s, 0, RATE / 100) < 0.1 * PEAK);
        assertTrue(rms(s, RATE / 2, RATE) > 0.65 * PEAK); // full-level sine RMS is 0.707
    }

    @Test
    public void neverClips() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] s = render(p, 30);
        for (short v : s) {
            assertTrue(Math.abs(v) <= PEAK + 1);
        }
    }

    @Test
    public void pulseSegmentAlternatesBurstsAndSilence() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] s = render(p, 30);
        int window = RATE / 50; // 20 ms
        int loud = 0;
        int silent = 0;
        int windows = 0;
        for (int i = (int) (11.0 * RATE); i + window < (int) (19.0 * RATE); i += window) {
            double r = rms(s, i, i + window);
            if (r > 0.5 * PEAK) {
                loud++;
            } else if (r < 0.01 * PEAK) {
                silent++;
            }
            windows++;
        }
        assertTrue("bursts", loud > windows * 0.4);
        assertTrue("gaps", silent > windows * 0.2);
        assertEquals(165.0, frequency(s, 11.0, 11.2), 3); // inside one burst
    }

    @Test
    public void sweepCoversLowToHighRange() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] s = render(p, 30);
        double min = Double.MAX_VALUE;
        double max = 0;
        for (double t = 19.6; t + 0.2 < 29.9; t += 0.2) {
            double f = frequency(s, t, t + 0.2);
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        assertTrue("min " + min, min >= ToneEngine.SWEEP_LOW - 5 && min < 140);
        assertTrue("max " + max, max <= ToneEngine.SWEEP_HIGH + 5 && max > 320);
    }

    @Test
    public void noClicksAnywhereInTheProgram() {
        for (int seconds : new int[]{30, 60, 90}) {
            ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, seconds, 0, ToneEngine.WAVE_SINE);
            short[] s = render(p, seconds);
            assertTrue(seconds + "s max step " + maxStep(s), maxStep(s) <= MAX_STEP_350);
        }
    }

    @Test
    public void finishesExactlyAfterDuration() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        render(p, 29.9);
        assertTrue(!p.isFinished());
        render(p, 0.2);
        assertTrue(p.isFinished());
        assertEquals(30L * RATE, p.getFramesDone());
        assertEquals(ToneEngine.PHASE_SWEEP, p.getCurrentPhase());
    }

    @Test
    public void manualFrequencyFollowsSliderWithoutClicks() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_MANUAL, 0, 200, ToneEngine.WAVE_SINE);
        short[] a = render(p, 1);
        assertEquals(200.0, frequency(a, 0.3, 1), 0.5);
        p.setManualFrequency(300);
        short[] b = render(p, 1);
        assertEquals(300.0, frequency(b, 0.3, 1), 0.5);
        assertTrue(maxStep(b) <= MAX_STEP_350);
        assertEquals(ToneEngine.MANUAL_MAX_SECONDS * (long) RATE, p.getTotalFrames());
    }

    @Test
    public void manualFrequencyIsClamped() {
        assertEquals(ToneEngine.MIN_FREQ, ToneProgram.clampFreq(5), 0);
        assertEquals(ToneEngine.MAX_FREQ, ToneProgram.clampFreq(5000), 0);
        assertEquals(165f, ToneProgram.clampFreq(165), 0);
    }

    @Test
    public void squareWaveIsStrongerThanSine() {
        ToneProgram sine = new ToneProgram(ToneEngine.MODE_MANUAL, 0, 165, ToneEngine.WAVE_SINE);
        ToneProgram square = new ToneProgram(ToneEngine.MODE_MANUAL, 0, 165, ToneEngine.WAVE_SQUARE);
        short[] a = render(sine, 1);
        short[] b = render(square, 1);
        assertTrue(rms(b, RATE / 2, RATE) > 1.2 * rms(a, RATE / 2, RATE));
        assertEquals(165.0, frequency(b, 0.3, 1), 0.5);
    }

    @Test
    public void fadeOutEndsInSilenceWithoutClicks() {
        ToneProgram p = new ToneProgram(ToneEngine.MODE_AUTO, 30, 0, ToneEngine.WAVE_SINE);
        short[] body = render(p, 2.02); // ends mid-cycle (near a peak), where a hard stop would click
        assertTrue(Math.abs(body[body.length - 1]) > 0.8 * PEAK);
        short[] tail = new short[BLOCK * 3];
        p.fillFadeOut(tail);
        short[] all = new short[body.length + tail.length];
        System.arraycopy(body, 0, all, 0, body.length);
        System.arraycopy(tail, 0, all, body.length, tail.length);
        assertTrue(Math.abs(tail[tail.length - 1]) < 50);
        assertTrue(maxStep(all) <= MAX_STEP_350);
        assertEquals(0f, p.getCurrentAmp(), 0);
    }

    @Test
    public void pulseEnvelopeShape() {
        assertEquals(0.0, ToneProgram.pulseEnvelope(0), 1e-9);
        assertEquals(1.0, ToneProgram.pulseEnvelope(0.1), 1e-9);
        assertEquals(0.0, ToneProgram.pulseEnvelope(0.3), 1e-9);
        assertEquals(1.0, ToneProgram.pulseEnvelope(0.5), 1e-9); // next period
    }
}
