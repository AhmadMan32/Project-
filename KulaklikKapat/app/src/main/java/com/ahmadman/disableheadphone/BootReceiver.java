package com.ahmadman.disableheadphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the service after a reboot (if enabled) or after an app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if ((boot && Prefs.startOnBoot(context)) || (updated && Prefs.enabled(context))) {
            try {
                SpeakerService.start(context);
            } catch (RuntimeException ignored) {
                // Background start not allowed on this device; user can start it from the app.
            }
        }
    }
}
