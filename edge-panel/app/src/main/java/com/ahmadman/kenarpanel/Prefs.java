package com.ahmadman.kenarpanel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Handle position and look, panel apps and animation speed. */
final class Prefs {

    static final int MAX_APPS = 12;

    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return p.getBoolean("enabled", false);
    }

    void enabled(boolean on) {
        p.edit().putBoolean("enabled", on).apply();
    }

    boolean right() {
        return p.getBoolean("right", false);
    }

    void right(boolean on) {
        p.edit().putBoolean("right", on).apply();
    }

    /** Vertical position of the handle, 0 = top, 1 = bottom. */
    float position() {
        return p.getFloat("pos", 0.32f);
    }

    void position(float f) {
        p.edit().putFloat("pos", Math.max(0f, Math.min(1f, f))).apply();
    }

    int lengthDp() {
        return p.getInt("length", 96);
    }

    void lengthDp(int v) {
        p.edit().putInt("length", v).apply();
    }

    int thicknessDp() {
        return p.getInt("thickness", 5);
    }

    void thicknessDp(int v) {
        p.edit().putInt("thickness", v).apply();
    }

    int opacity() {
        return p.getInt("opacity", 70);
    }

    void opacity(int v) {
        p.edit().putInt("opacity", v).apply();
    }

    boolean locked() {
        return p.getBoolean("locked", false);
    }

    void locked(boolean on) {
        p.edit().putBoolean("locked", on).apply();
    }

    boolean haptics() {
        return p.getBoolean("haptics", true);
    }

    void haptics(boolean on) {
        p.edit().putBoolean("haptics", on).apply();
    }


    boolean boot() {
        return p.getBoolean("boot", true);
    }

    void boot(boolean on) {
        p.edit().putBoolean("boot", on).apply();
    }

    com.ahmadman.kenarpanel.core.Motion.Speed speed() {
        return com.ahmadman.kenarpanel.core.Motion.Speed.of(p.getString("speed", "FAST"));
    }

    void speed(com.ahmadman.kenarpanel.core.Motion.Speed s) {
        p.edit().putString("speed", s.name()).apply();
    }

    /** The TV remote next to the app column is open (it can be folded away to save space). */
    boolean remoteOpen() {
        return p.getBoolean("remoteOpen", true);
    }

    void remoteOpen(boolean open) {
        p.edit().putBoolean("remoteOpen", open).apply();
    }

    /** Which remote the panel shows: "lg" (infrared) or "atv" (Android TV over Wi-Fi). */
    String tvMode() {
        return p.getString("tvMode", "lg");
    }

    void tvMode(String mode) {
        p.edit().putString("tvMode", mode).apply();
    }

    /** The paired Android TV: name, address, port and its certificate fingerprint. */
    String atvName() {
        return p.getString("atvName", null);
    }

    String atvHost() {
        return p.getString("atvHost", null);
    }

    int atvPort() {
        return p.getInt("atvPort", com.ahmadman.kenarpanel.atv.Messages.REMOTE_PORT);
    }

    String atvPin() {
        return p.getString("atvPin", null);
    }

    boolean atvPaired() {
        return atvHost() != null && atvPin() != null;
    }

    void atv(String name, String host, int port, String pin) {
        p.edit().putString("atvName", name).putString("atvHost", host).putInt("atvPort", port)
                .putString("atvPin", pin).apply();
    }

    /** The TV got a new address on the Wi-Fi (found again by name). */
    void atvHost(String host, int port) {
        p.edit().putString("atvHost", host).putInt("atvPort", port).apply();
    }

    void forgetAtv() {
        p.edit().remove("atvName").remove("atvHost").remove("atvPort").remove("atvPin").apply();
    }

    List<String> apps() {
        String s = p.getString("apps", "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) {
            out.addAll(Arrays.asList(s.split(",")));
        }
        return out;
    }

    void apps(List<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size() && i < MAX_APPS; i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(pkgs.get(i));
        }
        p.edit().putString("apps", sb.toString()).apply();
    }

    boolean appsChosen() {
        return p.contains("apps");
    }
}
