package com.ahmadman.notifsaver;

import android.content.Context;
import android.content.SharedPreferences;

import com.ahmadman.notifsaver.core.Rules;

import java.util.HashSet;
import java.util.Set;

/** User choices, cached in memory so the listener can decide in microseconds. */
final class Prefs {

    private static volatile Prefs instance;

    boolean onlySelected;
    Set<String> selected = new HashSet<>();
    Set<String> excluded = new HashSet<>();
    boolean saveOngoing;
    boolean keepAlive = true;
    boolean lock;
    int retentionDays;

    private final SharedPreferences prefs;
    private final String ownPackage;

    private Prefs(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        ownPackage = c.getPackageName();
        load();
    }

    static Prefs get(Context c) {
        Prefs s = instance;
        if (s == null) {
            synchronized (Prefs.class) {
                if (instance == null) {
                    instance = new Prefs(c);
                }
                s = instance;
            }
        }
        return s;
    }

    /** Forgets the cached instance (tests). */
    static void reset() {
        instance = null;
    }

    private void load() {
        onlySelected = prefs.getBoolean("onlySelected", false);
        selected = new HashSet<>(prefs.getStringSet("selected", new HashSet<>()));
        excluded = new HashSet<>(prefs.getStringSet("excluded", new HashSet<>()));
        saveOngoing = prefs.getBoolean("saveOngoing", false);
        keepAlive = prefs.getBoolean("keepAlive", true);
        lock = prefs.getBoolean("lock", false);
        retentionDays = prefs.getInt("retentionDays", 0);
    }

    synchronized void save() {
        prefs.edit()
                .putBoolean("onlySelected", onlySelected)
                .putStringSet("selected", new HashSet<>(selected))
                .putStringSet("excluded", new HashSet<>(excluded))
                .putBoolean("saveOngoing", saveOngoing)
                .putBoolean("keepAlive", keepAlive)
                .putBoolean("lock", lock)
                .putInt("retentionDays", retentionDays)
                .apply();
    }

    boolean shouldRecord(String pkg) {
        return Rules.shouldRecord(pkg, onlySelected, selected, excluded, ownPackage);
    }

    /** Whether the app is currently recorded (for the switch in the app list). */
    boolean isOn(String pkg) {
        return onlySelected ? selected.contains(pkg) : !excluded.contains(pkg);
    }

    synchronized void setOn(String pkg, boolean on) {
        if (onlySelected) {
            if (on) {
                selected.add(pkg);
            } else {
                selected.remove(pkg);
            }
        } else {
            if (on) {
                excluded.remove(pkg);
            } else {
                excluded.add(pkg);
            }
        }
        save();
    }
}
