package com.ahmadman.watereject;

import android.content.Context;
import android.content.SharedPreferences;

/** App settings. */
final class Prefs {

    private static final String FILE = "settings";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static int mode(Context c) {
        return sp(c).getInt("mode", ToneEngine.MODE_AUTO);
    }

    static void setMode(Context c, int v) {
        sp(c).edit().putInt("mode", v).apply();
    }

    /** Automatic program length in seconds: 30, 60 or 90. */
    static int duration(Context c) {
        return sp(c).getInt("duration", 30);
    }

    static void setDuration(Context c, int v) {
        sp(c).edit().putInt("duration", v).apply();
    }

    static int target(Context c) {
        return sp(c).getInt("target", ToneEngine.TARGET_SPEAKER);
    }

    static void setTarget(Context c, int v) {
        sp(c).edit().putInt("target", v).apply();
    }

    static float frequency(Context c) {
        return sp(c).getFloat("frequency", ToneEngine.BASE_FREQ);
    }

    static void setFrequency(Context c, float v) {
        sp(c).edit().putFloat("frequency", v).apply();
    }

    static int wave(Context c) {
        return sp(c).getInt("wave", ToneEngine.WAVE_SINE);
    }

    static void setWave(Context c, int v) {
        sp(c).edit().putInt("wave", v).apply();
    }

    static boolean maxVolume(Context c) {
        return sp(c).getBoolean("max_volume", true);
    }

    static void setMaxVolume(Context c, boolean v) {
        sp(c).edit().putBoolean("max_volume", v).apply();
    }

    static boolean vibrate(Context c) {
        return sp(c).getBoolean("vibrate", true);
    }

    static void setVibrate(Context c, boolean v) {
        sp(c).edit().putBoolean("vibrate", v).apply();
    }

    /** Force the speaker even if the system thinks headphones are plugged in. */
    static boolean forceSpeaker(Context c) {
        return sp(c).getBoolean("force_speaker", true);
    }

    static void setForceSpeaker(Context c, boolean v) {
        sp(c).edit().putBoolean("force_speaker", v).apply();
    }
}
