package com.ahmadman.kenarpanel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the handle back after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        Prefs p = new Prefs(c);
        if (p.enabled() && (p.boot() || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))) {
            OverlayService.start(c);
        }
    }
}
