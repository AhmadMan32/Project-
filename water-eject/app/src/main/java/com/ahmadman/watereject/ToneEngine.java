package com.ahmadman.watereject;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

/**
 * Plays the water-ejection sound ({@link ToneProgram}) on its own audio thread and routes it
 * to the built-in speaker or the earpiece.
 */
final class ToneEngine {

    static final int MODE_AUTO = 0;
    static final int MODE_MANUAL = 1;

    static final int TARGET_SPEAKER = 0;
    static final int TARGET_EARPIECE = 1;

    static final int WAVE_SINE = 0;
    static final int WAVE_SQUARE = 1;

    static final int PHASE_STEADY = 0;
    static final int PHASE_PULSE = 1;
    static final int PHASE_SWEEP = 2;
    static final int PHASE_MANUAL = 3;

    static final float BASE_FREQ = 165f;
    static final float SWEEP_LOW = 120f;
    static final float SWEEP_HIGH = 350f;
    static final float MIN_FREQ = 50f;
    static final float MAX_FREQ = 500f;
    /** Safety limit for manual mode. */
    static final int MANUAL_MAX_SECONDS = 180;

    private static final int RATE = ToneProgram.RATE;
    private static final int BLOCK = 960; // 20 ms

    interface Callback {
        /** Called on the audio thread when playback has fully ended. */
        void onEngineFinished(boolean completed);
    }

    private final int mode;
    private final int target;
    private final ToneProgram program;
    private final Callback callback;

    private volatile boolean stopRequested;
    private volatile AudioTrack track;
    private Thread thread;

    ToneEngine(int mode, int durationSeconds, int target, float freq, int wave, Callback callback) {
        this.mode = mode;
        this.target = target;
        this.program = new ToneProgram(mode, durationSeconds, freq, wave);
        this.callback = callback;
    }

    int getMode() {
        return mode;
    }

    int getTarget() {
        return target;
    }

    void setManualFrequency(float f) {
        program.setManualFrequency(f);
    }

    void setManualWave(int wave) {
        program.setManualWave(wave);
    }

    long getElapsedMs() {
        return program.getFramesDone() * 1000L / RATE;
    }

    long getTotalMs() {
        return program.getTotalFrames() * 1000L / RATE;
    }

    float getProgress() {
        long total = program.getTotalFrames();
        return total == 0 ? 0f : program.getFramesDone() / (float) total;
    }

    float getCurrentFreq() {
        return program.getCurrentFreq();
    }

    float getCurrentAmp() {
        return program.getCurrentAmp();
    }

    int getCurrentPhase() {
        return program.getCurrentPhase();
    }

    /** Device the track is actually playing on (Android 7+), or null if unknown. */
    AudioDeviceInfo getRoutedDevice() {
        AudioTrack t = track;
        if (t == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return null;
        }
        try {
            return t.getRoutedDevice();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Creates the track, asks for the built-in speaker / earpiece and starts the generator.
     *
     * @return false if the audio track could not be created.
     */
    boolean start(AudioDeviceInfo preferredDevice) {
        boolean earpiece = target == TARGET_EARPIECE;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(earpiece ? AudioAttributes.USAGE_VOICE_COMMUNICATION : AudioAttributes.USAGE_MEDIA)
                .setContentType(earpiece ? AudioAttributes.CONTENT_TYPE_SPEECH : AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min, BLOCK * 2 * 4);
        try {
            track = new AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return false;
            }
            if (preferredDevice != null) {
                track.setPreferredDevice(preferredDevice);
            }
            track.play();
        } catch (RuntimeException e) {
            if (track != null) {
                track.release();
                track = null;
            }
            return false;
        }
        thread = new Thread(this::run, "water-eject-tone");
        thread.start();
        return true;
    }

    /** Fades out and stops; {@link Callback#onEngineFinished} follows shortly. */
    void requestStop() {
        stopRequested = true;
    }

    /** Stops and waits for the thread (used when the service is being destroyed). */
    void stopNow() {
        stopRequested = true;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Generator
    // ---------------------------------------------------------------------------------------

    private void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack t = track;
        short[] buf = new short[BLOCK];
        boolean completed = false;
        try {
            while (!stopRequested) {
                if (program.isFinished()) {
                    completed = true;
                    break;
                }
                program.fill(buf);
                if (t.write(buf, 0, BLOCK) < 0) {
                    break;
                }
            }
            // Short fade out so the speaker does not "click".
            short[] tail = new short[BLOCK * 3];
            program.fillFadeOut(tail);
            t.write(tail, 0, tail.length);
            // Let the buffered audio play out before stopping.
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Track died (e.g. audio server restart); finish below.
        } finally {
            try {
                t.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            t.release();
            track = null;
            callback.onEngineFinished(completed);
        }
    }
}
