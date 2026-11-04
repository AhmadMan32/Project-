package com.ahmadman.linkdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import com.ahmadman.linkdownloader.core.Commands;
import com.ahmadman.linkdownloader.core.ErrorHints;
import com.ahmadman.linkdownloader.core.LinkDetector;
import com.ahmadman.linkdownloader.core.Platform;
import com.ahmadman.linkdownloader.core.Progress;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import java.io.File;
import java.util.List;

/** Works through the download queue one item at a time, in the foreground (screen off is fine). */
public class DownloadService extends Service {

    static final String ACTION_CANCEL = "com.ahmadman.linkdownloader.CANCEL";
    static final String EXTRA_ID = "id";

    private static final String CHANNEL_PROGRESS = "progress";
    private static final String CHANNEL_DONE = "done";
    private static final int ID_PROGRESS = 1;
    private static volatile boolean running;
    private static volatile boolean foreground;

    private final Object lock = new Object();
    private boolean workerActive;
    private int lastStartId;
    private long lastNotify;

    static void start(Context c) {
        try {
            Intent i = new Intent(c, DownloadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException ignored) {
            // background start refused: starts next time the app is opened
        }
    }

    static boolean isRunning() {
        return running;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = progress(getString(R.string.notif_preparing), null, -1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ID_PROGRESS, n);
            }
            foreground = true;
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            Downloads.get(this).cancel(intent.getStringExtra(EXTRA_ID));
        }
        synchronized (lock) {
            lastStartId = startId;
            if (!workerActive) {
                workerActive = true;
                running = true;
                new Thread(this::loop, "downloads").start();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        foreground = false;
        super.onDestroy();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 daily limit for data sync services: stop the current download cleanly
        Item it = currentItem;
        if (it != null) {
            Engine.cancel(it.id);
        }
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private volatile Item currentItem;

    private void loop() {
        Downloads d = Downloads.get(this);
        int stopId;
        while (true) {
            Item it;
            synchronized (lock) {
                it = d.next();
                if (it == null) {
                    // Decide under the lock: a start request arriving now gets a new worker
                    // and a newer start id, so stopSelf(stopId) below will not stop it.
                    workerActive = false;
                    running = false;
                    stopId = lastStartId;
                    break;
                }
            }
            currentItem = it;
            try {
                download(it);
            } catch (Throwable t) {
                android.util.Log.e("LinkDownloader", "download crashed", t);
            }
            currentItem = null;
        }
        stopSelf(stopId);
    }

    private void download(Item it) {
        Downloads d = Downloads.get(this);
        it.status = Item.RUNNING;
        it.percent = 0;
        it.detail = getString(R.string.step_starting);
        d.changed(true);
        notifyProgress(it, true);

        File dir = new File(getCacheDir(), "dl/" + it.id);
        deleteRecursive(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        Platform platform = LinkDetector.detect(it.url);
        String cookies = Engine.cookiesFor(this, platform);
        String template = Commands.template(dir.getAbsolutePath());
        boolean preferH264 = Engine.prefs(this).getBoolean("prefer_h264", true);
        List<String> args = it.isVideo()
                ? Commands.video(it.height, preferH264, template, cookies)
                : Commands.audio(it.audioFormat == null ? Commands.MP3 : it.audioFormat, template, cookies);
        final int[] part = {1};
        final float[] last = {0};
        try {
            YoutubeDLResponse r = Engine.run(this, it.url, args, it.id, (percent, line) -> {
                Progress p = Progress.parse(line);
                if (p == null) {
                    return;
                }
                if (p.step == Progress.Step.DOWNLOADING) {
                    if (last[0] > 90 && p.percent < 10 && it.isVideo()) {
                        part[0]++; // video stream done, now the audio stream
                    }
                    last[0] = p.percent;
                    it.percent = p.percent;
                    StringBuilder sb = new StringBuilder();
                    if (it.isVideo()) {
                        sb.append(getString(part[0] == 1 ? R.string.step_video : R.string.step_audio_part)).append(" · ");
                    }
                    sb.append(Math.round(p.percent)).append('%');
                    if (p.total != null) {
                        sb.append(" · ").append(p.total.replace("i", ""));
                    }
                    if (p.speed != null) {
                        sb.append(" · ").append(p.speed.replace("i", ""));
                    }
                    if (p.eta != null && p.percent < 100) {
                        sb.append(" · ").append(getString(R.string.eta, p.eta));
                    }
                    it.detail = sb.toString();
                } else {
                    it.percent = 100;
                    it.detail = getString(p.step == Progress.Step.MERGING ? R.string.step_merging
                            : p.step == Progress.Step.CONVERTING ? R.string.step_converting : R.string.step_finishing);
                }
                d.changed(false);
                notifyProgress(it, false);
            });
            File result = resultFile(r.getOut(), dir);
            if (result == null) {
                throw new IllegalStateException(getString(R.string.err_no_file));
            }
            it.detail = getString(R.string.step_saving);
            d.changed(false);
            String quality = MediaSaver.quality(result, it.isVideo());
            Uri uri = MediaSaver.save(this, result, it.isVideo());
            it.outputUri = uri.toString();
            it.fileName = result.getName();
            it.size = result.length();
            it.quality = quality;
            if (it.title == null || it.title.isEmpty()) {
                it.title = titleFromFile(result.getName());
            }
            it.status = Item.DONE;
            it.percent = 100;
            it.detail = "";
            notifyDone(it, true);
        } catch (YoutubeDL.CanceledException e) {
            it.status = Item.CANCELLED;
            it.detail = "";
        } catch (Throwable e) {
            if (it.cancelRequested || Item.CANCELLED.equals(it.status)) {
                it.status = Item.CANCELLED;
                it.detail = "";
            } else {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                it.status = Item.FAILED;
                it.reason = ErrorHints.classify(msg).name();
                it.error = ErrorHints.lastError(msg);
                it.detail = "";
                notifyDone(it, false);
            }
        } finally {
            deleteRecursive(dir);
            d.changed(true);
        }
    }

    /** The printed final path, else the biggest finished file in the job folder. */
    static File resultFile(String stdout, File dir) {
        String printed = Commands.findPath(stdout);
        if (printed != null) {
            File f = new File(printed);
            if (f.isFile()) {
                return f;
            }
        }
        File best = null;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.endsWith(".jpg")
                        && !n.endsWith(".webp") && !n.endsWith(".png") && !n.contains(".temp.")
                        && (best == null || f.length() > best.length())) {
                    best = f;
                }
            }
        }
        return best;
    }

    /** "Title [id].mp4" -> "Title". */
    static String titleFromFile(String name) {
        String s = name.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
        return s.replaceAll("\\s*\\[[^\\]]+]$", "");
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------------

    private void channels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel p = new NotificationChannel(CHANNEL_PROGRESS, getString(R.string.channel_progress),
                    NotificationManager.IMPORTANCE_LOW);
            p.setShowBadge(false);
            nm.createNotificationChannel(p);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, getString(R.string.channel_done),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        channels();
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification progress(String title, Item it, float percent) {
        Notification.Builder b = builder(CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp());
        if (it != null) {
            b.setContentText(it.detail);
            PendingIntent cancel = PendingIntent.getService(this, it.id.hashCode(),
                    new Intent(this, DownloadService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, it.id),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.cancel), cancel).build());
        }
        if (percent >= 0) {
            b.setProgress(100, Math.round(percent), false);
        } else {
            b.setProgress(0, 0, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void notifyProgress(Item it, boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotify < 800) {
            return;
        }
        lastNotify = now;
        String title = it.title != null && !it.title.isEmpty() ? it.title : it.url;
        getSystemService(NotificationManager.class).notify(ID_PROGRESS,
                progress((it.isVideo() ? "MP4 · " : "MP3 · ") + title, it, it.percent));
    }

    private void notifyDone(Item it, boolean ok) {
        Notification.Builder b = builder(CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(ok ? R.string.notif_done : R.string.notif_failed))
                .setContentText(it.title != null ? it.title : it.url)
                .setAutoCancel(true);
        if (ok && it.outputUri != null) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it.outputUri),
                    MediaSaver.mime(it.fileName == null ? "" : it.fileName))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(this, it.id.hashCode(), view,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            b.setContentIntent(openApp());
        }
        getSystemService(NotificationManager.class).notify(it.id.hashCode(), b.build());
    }
}
