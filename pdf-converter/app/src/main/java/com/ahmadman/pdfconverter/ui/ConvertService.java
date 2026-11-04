package com.ahmadman.pdfconverter.ui;

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

import com.ahmadman.pdfconverter.R;

/**
 * Keeps the app alive while a conversion runs in the background (screen off, other app open)
 * and shows its progress; the work itself happens in {@link JobRunner}.
 */
public class ConvertService extends Service {

    private static final String CHANNEL_PROGRESS = "progress";
    private static final String CHANNEL_DONE = "done";
    private static final int ID_PROGRESS = 1;
    private static final int ID_DONE = 2;
    private static long lastUpdate;
    private static volatile boolean running;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, ConvertService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
            // not allowed right now: the job still runs while the app is visible
        }
    }

    /** Throttled progress notification update. */
    static void update(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (!running || now - lastUpdate < 700) {
            return;
        }
        lastUpdate = now;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ID_PROGRESS, progress(c));
    }

    static void finished(Context c) {
        // Only stop a service that already called startForeground: stopping it earlier crashes
        // on Android 8+. Otherwise onStartCommand sees the finished job and stops itself.
        if (running) {
            c.stopService(new Intent(c, ConvertService.class));
        }
        JobRunner r = JobRunner.get();
        if (r.state() == JobRunner.DONE || r.state() == JobRunner.FAILED) {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            channels(c);
            Tool tool = Tools.byId(r.toolId());
            Notification.Builder b = builder(c, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(c.getString(r.state() == JobRunner.DONE ? R.string.notif_done : R.string.notif_failed))
                    .setContentText(tool == null ? "" : c.getString(tool.title))
                    .setAutoCancel(true)
                    .setContentIntent(openTool(c));
            nm.notify(ID_DONE, b.build());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = progress(this);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID_PROGRESS, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (JobRunner.get().state() != JobRunner.RUNNING) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId) {
        JobRunner.get().cancel();
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 data sync time limit: cancel rather than being killed.
        JobRunner.get().cancel();
        stopSelf();
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

    private static void channels(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel p = new NotificationChannel(CHANNEL_PROGRESS, c.getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW);
            p.setShowBadge(false);
            nm.createNotificationChannel(p);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, c.getString(R.string.channel_done),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(c, channel)
                : new Notification.Builder(c);
    }

    private static PendingIntent openTool(Context c) {
        Intent i = new Intent(c, ToolActivity.class)
                .putExtra(ToolActivity.EXTRA_TOOL, JobRunner.get().toolId())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Notification progress(Context c) {
        channels(c);
        JobRunner r = JobRunner.get();
        Tool tool = Tools.byId(r.toolId());
        Notification.Builder b = builder(c, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tool == null ? c.getString(R.string.app_name) : c.getString(tool.title))
                .setContentText(r.message())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openTool(c));
        if (r.total() > 0) {
            b.setProgress(r.total(), Math.min(r.done(), r.total()), false);
        } else {
            b.setProgress(0, 0, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
