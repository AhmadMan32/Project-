package com.ahmadman.notifsaver;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * Called by Android for every notification, the moment it arrives (and whenever it is
 * updated). Runs as long as "Notification access" is on, also after reboots.
 */
public class ListenerService extends NotificationListenerService {

    static volatile boolean connected;

    static ComponentName component(Context c) {
        return new ComponentName(c, ListenerService.class);
    }

    /** Is "Notification access" granted to this app? */
    static boolean isEnabled(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm != null) {
                return nm.isNotificationListenerAccessGranted(component(c));
            }
        }
        String flat = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(component(c).flattenToString());
    }

    /** Asks the system to reconnect the listener (it can get unbound on some phones). */
    static void rebind(Context c, boolean hard) {
        if (!isEnabled(c) || connected) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(component(c));
            } catch (RuntimeException ignored) {
                // not allowed right now
            }
        }
        if (hard) {
            // toggling the component makes the system bind it again
            PackageManager pm = c.getPackageManager();
            pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(component(c), PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        // save what is already in the notification shade
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    handle(sbn);
                }
            }
        } catch (RuntimeException ignored) {
            // not available yet
        }
        Store.get(this).cleanup();
        if (Prefs.get(this).keepAlive) {
            KeepAliveService.start(this);
        }
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        rebind(this, false);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        handle(sbn);
    }

    private void handle(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        Prefs s = Prefs.get(this);
        if (!s.shouldRecord(sbn.getPackageName())) {
            return;
        }
        try {
            Store store = Store.get(this);
            List<com.ahmadman.notifsaver.core.Capture> captures = new ArrayList<>(Parser.parse(sbn,
                    store.label(sbn.getPackageName()), s.saveOngoing, getString(R.string.self_sender)));
            store.save(captures);
        } catch (RuntimeException e) {
            android.util.Log.e("NotifSaver", "parse failed for " + sbn.getPackageName(), e);
        }
    }
}
