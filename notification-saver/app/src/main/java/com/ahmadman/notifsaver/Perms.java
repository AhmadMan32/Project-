package com.ahmadman.notifsaver;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;

import java.util.Locale;

/** What the app needs, and the system screens where the user can switch it on. */
final class Perms {

    private Perms() {
    }

    /** Android 13+ blocks "Notification access" for sideloaded apps until restricted settings are allowed. */
    static boolean restrictedSettingsApply() {
        return Build.VERSION.SDK_INT >= 33;
    }

    static boolean postNotifications(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    static boolean batteryUnrestricted(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

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

    static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    private static Uri packageUri(Context c) {
        return Uri.fromParts("package", c.getPackageName(), null);
    }

    static void openListenerSettings(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent detail = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ListenerService.component(a).flattenToString());
            if (Ui.open(a, detail)) {
                return;
            }
        }
        if (!Ui.open(a, new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
            openAppDetails(a);
        }
    }

    static void openAppDetails(Activity a) {
        Ui.open(a, new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(a)));
    }

    static void openPostNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !a.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            a.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        Ui.open(a, new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.getPackageName()));
    }

    /** Asks once (Android 13+) so the quiet "recording" notification can be shown. */
    static void askPostNotificationsOnce(Activity a) {
        if (Build.VERSION.SDK_INT >= 33 && !postNotifications(a)
                && !a.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            openPostNotifications(a);
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    static void openBattery(Activity a) {
        if (!Ui.open(a, new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(a)))) {
            Ui.open(a, new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    static void openHibernation(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Ui.open(a, new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri(a)))) {
            return;
        }
        openAppDetails(a);
    }

    /** MIUI / HyperOS: autostart list first, then the app's permission editor. */
    static void openXiaomi(Activity a) {
        Intent autostart = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (Ui.open(a, autostart)) {
            return;
        }
        Intent miui = new Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", a.getPackageName());
        if (!Ui.open(a, miui)) {
            openAppDetails(a);
        }
    }
}
