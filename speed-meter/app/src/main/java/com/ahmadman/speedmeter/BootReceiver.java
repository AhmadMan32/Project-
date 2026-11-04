package com.ahmadman.speedmeter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the meter after a reboot (if enabled) or an app update (if it was running). */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        android.content.SharedPreferences p = Style.prefs(context);
        if ((boot && p.getBoolean("startOnBoot", false)) || (updated && p.getBoolean("wasRunning", false))) {
            try {
                MonitorService.start(context);
            } catch (RuntimeException ignored) {
                // not allowed now; the user can start it from the app
            }
        }
    }
}
