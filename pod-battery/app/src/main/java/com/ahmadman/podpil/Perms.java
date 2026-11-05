package com.ahmadman.podpil;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** Permissions the app needs and where to switch them on. */
final class Perms {

    private Perms() {
    }

    /** Runtime permissions for reading the AirPods (and the notification on Android 13+). */
    static String[] runtime() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    static boolean granted(Context c, String p) {
        return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean bluetooth(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return granted(c, Manifest.permission.BLUETOOTH_SCAN) && granted(c, Manifest.permission.BLUETOOTH_CONNECT);
        }
        return granted(c, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /** Before Android 12 Bluetooth scans also need location to be switched on. */
    static boolean locationNeeded() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
    }

    @SuppressWarnings("deprecation")
    static boolean locationOn(Context c) {
        if (!locationNeeded()) {
            return true;
        }
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return lm.isLocationEnabled();
        }
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    static boolean notifications(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return true;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    static boolean overlay(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static boolean batteryUnrestricted(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
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

    static void openAppDetails(Activity a) {
        open(a, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(a)));
    }

    static void openOverlay(Activity a) {
        open(a, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg(a)));
    }

    static void openNotifications(Activity a) {
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

    static void openLocation(Activity a) {
        open(a, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }
}
