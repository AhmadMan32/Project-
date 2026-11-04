package com.ahmadman.notifsaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot / app update: start the keep-alive service and nudge the listener. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        if (Prefs.get(c).keepAlive && ListenerService.isEnabled(c)) {
            KeepAliveService.start(c);
        }
        ListenerService.rebind(c, false);
    }
}
