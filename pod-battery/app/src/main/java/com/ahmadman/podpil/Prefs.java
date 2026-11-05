package com.ahmadman.podpil;

import android.content.Context;
import android.content.SharedPreferences;

import com.ahmadman.podpil.core.Crypto;
import com.ahmadman.podpil.core.Status;

/** Settings, the AirPods' keys and the measured drain rate. */
final class Prefs {

    /** Result of the last attempt to open the AirPods' accessory channel. */
    static final int AAP_UNTRIED = 0;
    static final int AAP_OK = 1;
    static final int AAP_FAILED = 2;

    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    boolean popup() {
        return p.getBoolean("popup", true);
    }

    void popup(boolean on) {
        p.edit().putBoolean("popup", on).apply();
    }

    /** Low battery warning threshold in percent, 0 = off. */
    int lowAlert() {
        return p.getInt("lowAlert", 20);
    }

    void lowAlert(int v) {
        p.edit().putInt("lowAlert", v).apply();
    }

    boolean boot() {
        return p.getBoolean("boot", true);
    }

    void boot(boolean on) {
        p.edit().putBoolean("boot", on).apply();
    }

    byte[] irk() {
        return Crypto.hexKey(p.getString("irk", null));
    }

    byte[] encKey() {
        return Crypto.hexKey(p.getString("enc", null));
    }

    void keys(byte[] irk, byte[] enc) {
        SharedPreferences.Editor e = p.edit();
        if (irk != null) {
            e.putString("irk", Crypto.hex(irk));
        }
        if (enc != null) {
            e.putString("enc", Crypto.hex(enc));
        }
        e.apply();
    }

    void clearKeys() {
        p.edit().remove("irk").remove("enc").apply();
    }

    int aapState() {
        return p.getInt("aap", AAP_UNTRIED);
    }

    void aapState(int state) {
        if (state != aapState()) {
            p.edit().putInt("aap", state).apply();
        }
    }

    double learnedMpp(int model) {
        return Double.longBitsToDouble(p.getLong("mpp_" + model, 0));
    }

    int learnedSamples(int model) {
        return p.getInt("n_" + model, 0);
    }

    void learned(int model, double mpp, int samples) {
        p.edit().putLong("mpp_" + model, Double.doubleToLongBits(mpp)).putInt("n_" + model, samples).apply();
    }

    /** Last seen state, shown while the AirPods are away. */
    void last(Status s, String name) {
        p.edit()
                .putInt("l_model", s.modelId)
                .putInt("l_left", s.left)
                .putInt("l_right", s.right)
                .putInt("l_case", s.caseLevel)
                .putInt("l_flags", (s.leftCharging ? 1 : 0) | (s.rightCharging ? 2 : 0) | (s.caseCharging ? 4 : 0))
                .putString("l_source", s.source.name())
                .putLong("l_time", s.time)
                .putString("l_name", name)
                .apply();
    }

    Status last() {
        if (!p.contains("l_time")) {
            return null;
        }
        Status s = new Status();
        s.modelId = p.getInt("l_model", 0);
        s.left = p.getInt("l_left", Status.UNKNOWN);
        s.right = p.getInt("l_right", Status.UNKNOWN);
        s.caseLevel = p.getInt("l_case", Status.UNKNOWN);
        int f = p.getInt("l_flags", 0);
        s.leftCharging = (f & 1) != 0;
        s.rightCharging = (f & 2) != 0;
        s.caseCharging = (f & 4) != 0;
        try {
            s.source = Status.Source.valueOf(p.getString("l_source", "BROADCAST"));
        } catch (IllegalArgumentException e) {
            s.source = Status.Source.BROADCAST;
        }
        s.time = p.getLong("l_time", 0);
        return s;
    }

    String lastName() {
        return p.getString("l_name", null);
    }
}
