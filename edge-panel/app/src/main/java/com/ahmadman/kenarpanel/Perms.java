package com.ahmadman.kenarpanel;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/** Permissions and the system screens that grant them. */
final class Perms {

    private Perms() {
    }

    static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static boolean notifications(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    static boolean battery(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private static Uri pkg(Context c) {
        return Uri.fromParts("package", c.getPackageName(), null);
    }

    static boolean open(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    static void openOverlay(Activity a) {
        if (!open(a, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(a)))) {
            openAppDetails(a);
        }
    }

    static void openAppDetails(Activity a) {
        open(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(a)));
    }

    static void openNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !a.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("askedNotif", false)) {
            a.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("askedNotif", true).apply();
            a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            open(a, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,
                    a.getPackageName()));
        } else {
            openAppDetails(a);
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    static void openBattery(Activity a) {
        if (!open(a, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(a)))) {
            open(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** MIUI / HyperOS: "display pop-up windows while running in the background" and autostart. */
    static void openXiaomi(Activity a) {
        Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", a.getPackageName());
        if (open(a, miui)) {
            return;
        }
        Intent autostart = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (!open(a, autostart)) {
            openAppDetails(a);
        }
    }
}
