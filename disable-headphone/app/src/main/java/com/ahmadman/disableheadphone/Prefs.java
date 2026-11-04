package com.ahmadman.disableheadphone;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around the app's settings. */
final class Prefs {

    private static final String FILE = "settings";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_METHOD = "method";
    private static final String KEY_ALWAYS = "always_force";
    private static final String KEY_BLUETOOTH = "include_bluetooth";
    private static final String KEY_BOOT = "start_on_boot";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Whether the user last switched the service on. */
    static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    static int method(Context c) {
        int m = sp(c).getInt(KEY_METHOD, AudioRouter.METHOD_AUTO);
        if (m == AudioRouter.METHOD_FORCE_MEDIA && !AudioRouter.isForceMediaLikelySupported()) {
            return AudioRouter.METHOD_AUTO;
        }
        return m;
    }

    static void setMethod(Context c, int v) {
        sp(c).edit().putInt(KEY_METHOD, v).apply();
    }

    /** Force the speaker even when no headphone is detected. */
    static boolean alwaysForce(Context c) {
        return sp(c).getBoolean(KEY_ALWAYS, false);
    }

    static void setAlwaysForce(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ALWAYS, v).apply();
    }

    static boolean includeBluetooth(Context c) {
        return sp(c).getBoolean(KEY_BLUETOOTH, false);
    }

    static void setIncludeBluetooth(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_BLUETOOTH, v).apply();
    }

    static boolean startOnBoot(Context c) {
        return sp(c).getBoolean(KEY_BOOT, false);
    }

    static void setStartOnBoot(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_BOOT, v).apply();
    }
}
