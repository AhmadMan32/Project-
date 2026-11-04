package com.ahmadman.notifsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Optional "always on" mode: a quiet foreground service keeps the app's process alive on
 * phones that close background apps aggressively (Xiaomi, Huawei, some Samsung).
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "keepalive";
    private static final int ID = 1;
    private static volatile boolean running;
    private static long lastRefresh;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
            // background start refused; the listener still works while the system keeps it bound
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    /** Updates the "saved today" counter, at most every 30 seconds. */
    static void refresh(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (!running || now - lastRefresh < 30_000) {
            return;
        }
        lastRefresh = now;
        c.getSystemService(NotificationManager.class).notify(ID, build(c));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = build(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID, n);
            }
            running = true;
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Prefs.get(this).keepAlive) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Notification build(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_keepalive),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = c.getString(ListenerService.connected ? R.string.keepalive_text : R.string.keepalive_waiting);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(c.getString(R.string.keepalive_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }
}
