package com.ahmadman.disableheadphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service that keeps the headphone output "disabled": whenever a headphone is
 * detected (or always, if configured) it forces audio to the built-in speaker and keeps
 * re-applying it, because the system may reset the routing on device or mode changes.
 */
public class SpeakerService extends Service {

    static final String ACTION_START = "com.ahmadman.disableheadphone.START";
    static final String ACTION_STOP = "com.ahmadman.disableheadphone.STOP";
    static final String ACTION_REFRESH = "com.ahmadman.disableheadphone.REFRESH";

    private static final String CHANNEL_ID = "speaker_routing";
    private static final int NOTIFICATION_ID = 1;
    private static final long WATCHDOG_MS = 4000;
    private static final long DEBOUNCE_MS = 300;
    private static final long QUICK_RETRY_MS = 700;
    private static final int MAX_QUICK_RETRIES = 4;

    /** UI callbacks, always invoked on the main thread. */
    interface Listener {
        void onSpeakerStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile boolean sRunning;
    private static volatile boolean sForcing;
    private static volatile boolean sRoutingOk;
    private static volatile int sActiveMethod;

    static boolean isRunning() {
        return sRunning;
    }

    /** Headphone detected (or "always" on) and the service is trying to force the speaker. */
    static boolean isForcing() {
        return sForcing;
    }

    static boolean isRoutingOk() {
        return sRoutingOk;
    }

    static int getActiveMethod() {
        return sActiveMethod;
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void start(Context c) {
        Prefs.setEnabled(c, true);
        Intent i = new Intent(c, SpeakerService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        Prefs.setEnabled(c, false);
        c.stopService(new Intent(c, SpeakerService.class));
    }

    /** Re-reads the settings (method, Bluetooth...) if the service is running. */
    static void refresh(Context c) {
        if (sRunning) {
            try {
                c.startService(new Intent(c, SpeakerService.class).setAction(ACTION_REFRESH));
            } catch (RuntimeException ignored) {
                // The watchdog picks the new settings up within a few seconds anyway.
            }
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private AudioRouter router;
    private String lastSignature = "";
    private int quickRetries;
    private Object modeListener;

    private final Runnable evaluateRunnable = this::evaluate;
    private final Runnable adbListener = () -> scheduleEvaluate(DEBOUNCE_MS);

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            evaluate();
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            scheduleEvaluate(DEBOUNCE_MS);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            scheduleEvaluate(DEBOUNCE_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        router = new AudioRouter(this);
        AdbForce.init(this);
        AdbForce.addListener(adbListener);
        createChannel();
        am.registerAudioDeviceCallback(deviceCallback, handler);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnModeChangedListener l = mode -> scheduleEvaluate(DEBOUNCE_MS);
            am.addOnModeChangedListener(getMainExecutor(), l);
            modeListener = l;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!sRunning) {
            sRunning = true;
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, WATCHDOG_MS);
        }
        scheduleEvaluate(0);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        AdbForce.removeListener(adbListener);
        am.unregisterAudioDeviceCallback(deviceCallback);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener != null) {
            am.removeOnModeChangedListener((AudioManager.OnModeChangedListener) modeListener);
        }
        router.release();
        sRunning = false;
        sForcing = false;
        sRoutingOk = false;
        sActiveMethod = AudioRouter.METHOD_NONE;
        dispatch();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleEvaluate(long delayMs) {
        handler.removeCallbacks(evaluateRunnable);
        handler.postDelayed(evaluateRunnable, delayMs);
    }

    private void evaluate() {
        if (!sRunning) {
            return;
        }
        List<AudioDeviceInfo> headphones =
                AudioRouter.findHeadphones(am, Prefs.includeBluetooth(this));
        boolean force = !headphones.isEmpty() || Prefs.alwaysForce(this);
        boolean ok;
        if (force) {
            ok = router.engage(Prefs.method(this));
        } else {
            router.release();
            ok = true;
        }
        sForcing = force;
        sRoutingOk = ok;
        sActiveMethod = router.getActiveMethod();

        // Mode changes are applied asynchronously on newer Android versions: re-check soon.
        if (force && !ok && quickRetries < MAX_QUICK_RETRIES) {
            quickRetries++;
            scheduleEvaluate(QUICK_RETRY_MS);
        } else if (ok) {
            quickRetries = 0;
        }

        String signature = force + "/" + ok + "/" + sActiveMethod;
        if (!signature.equals(lastSignature)) {
            lastSignature = signature;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, buildNotification());
            dispatch();
        }
    }

    private void dispatch() {
        for (Listener l : LISTENERS) {
            l.onSpeakerStateChanged();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SpeakerTileService.requestUpdate(this);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification / foreground
    // ---------------------------------------------------------------------------------------

    private boolean goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, SpeakerService.class).setAction(ACTION_STOP), piFlags);

        String title;
        if (!sForcing) {
            title = getString(R.string.notif_waiting);
        } else if (sRoutingOk) {
            title = getString(R.string.notif_forcing);
        } else {
            title = getString(R.string.notif_failed);
        }
        String text = sForcing
                ? getString(R.string.notif_method, DeviceLabels.method(this, sActiveMethod))
                : getString(R.string.notif_waiting_text);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(getColor(R.color.primary))
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
