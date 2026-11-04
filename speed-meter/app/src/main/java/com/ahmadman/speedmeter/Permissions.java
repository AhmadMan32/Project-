package com.ahmadman.speedmeter;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/** What the meter needs, and where the user can switch it on. */
final class Permissions {

    private Permissions() {
    }

    static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static boolean notifications(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true; // Android 6 cannot tell; notifications are on by default there
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    static boolean batteryUnrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** "Pause app activity if unused" exists from Android 11. */
    static boolean hibernationApplies() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    static boolean hibernationOff(Context c) {
        if (!hibernationApplies()) {
            return true;
        }
        try {
            return c.getPackageManager().isAutoRevokeWhitelisted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Xiaomi / Redmi / POCO need extra "pop-up while in background" and autostart switches. */
    static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private static Uri packageUri(Context c) {
        return Uri.fromParts("package", c.getPackageName(), null);
    }

    static void openOverlay(Activity a) {
        open(a, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(a)));
    }

    static void openNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !a.shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)
                && !Style.prefs(a).getBoolean("askedNotifications", false)) {
            Style.prefs(a).edit().putBoolean("askedNotifications", true).apply();
            a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
        open(a, i);
    }

    @android.annotation.SuppressLint("BatteryLife")
    static void openBattery(Activity a) {
        if (!open(a, new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(a)))) {
            open(a, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    static void openHibernation(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (open(a, new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri(a)))) {
                return;
            }
        }
        openAppDetails(a);
    }

    static void openXiaomi(Activity a) {
        Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", a.getPackageName());
        if (!open(a, miui)) {
            openAppDetails(a);
        }
    }

    static void openAppDetails(Activity a) {
        open(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(a)));
    }

    private static boolean open(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
