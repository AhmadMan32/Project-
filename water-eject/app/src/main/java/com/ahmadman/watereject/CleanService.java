package com.ahmadman.watereject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground service running one cleaning session, so it keeps going with the screen off.
 * It plays the tone on the built-in speaker (or earpiece) and, like "Kulaklık Kapat",
 * forces the speaker if the system routes the sound to a (falsely) detected headphone.
 */
public class CleanService extends Service implements ToneEngine.Callback {

    static final String ACTION_START = "com.ahmadman.watereject.START";
    static final String ACTION_STOP = "com.ahmadman.watereject.STOP";

    private static final String CHANNEL_ID = "cleaning";
    private static final int NOTIFICATION_ID = 1;
    private static final long ROUTING_CHECK_MS = 1000;
    private static final long FIRST_ROUTING_CHECK_MS = 400;
    private static final long NOTIFICATION_TICK_MS = 1000;
    private static final long[] VIBRATION_PATTERN = {0, 450, 250};

    /** UI callbacks on the main thread when a session starts or ends. */
    interface Listener {
        void onCleanStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile ToneEngine sEngine;
    private static volatile boolean sRunning;
    private static volatile boolean sForced;
    private static volatile int sRoutedType = -1;
    private static volatile boolean sLastCompleted;

    static boolean isRunning() {
        return sRunning;
    }

    static ToneEngine engine() {
        return sEngine;
    }

    /** True when the speaker had to be forced (headphone detected). */
    static boolean isForced() {
        return sForced;
    }

    /** AudioDeviceInfo type the tone is playing on, or -1 if unknown. */
    static int getRoutedType() {
        return sRoutedType;
    }

    static boolean lastSessionCompleted() {
        return sLastCompleted;
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void start(Context c) {
        Intent i = new Intent(c, CleanService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        if (sRunning) {
            c.startService(new Intent(c, CleanService.class).setAction(ACTION_STOP));
        }
    }

    static void setManualFrequency(float f) {
        ToneEngine e = sEngine;
        if (e != null) {
            e.setManualFrequency(f);
        }
    }

    static void setManualWave(int wave) {
        ToneEngine e = sEngine;
        if (e != null) {
            e.setManualWave(wave);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager am;
    private AudioRouter router;
    private ToneEngine engine;
    private Object focusRequest;
    private Vibrator vibrator;
    private int savedMusic = -1;
    private int savedCall = -1;
    private boolean forceSpeaker;
    private boolean maxVolume;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            // A call or another app needs the audio: stop cleaning.
            handler.post(() -> {
                if (engine != null) {
                    engine.requestStop();
                }
            });
        }
    };

    private final Runnable routingCheck = new Runnable() {
        @Override
        public void run() {
            checkRouting();
            handler.postDelayed(this, ROUTING_CHECK_MS);
        }
    };

    private final Runnable notificationTick = new Runnable() {
        @Override
        public void run() {
            if (engine != null) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(NOTIFICATION_ID, buildNotification());
                handler.postDelayed(this, NOTIFICATION_TICK_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        router = new AudioRouter(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!goForeground()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            if (engine != null) {
                engine.requestStop();
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (engine == null && !begin()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.stopNow();
            engine = null;
        }
        cleanup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    private boolean begin() {
        int target = Prefs.target(this);
        AudioDeviceInfo earpiece = AudioRouter.findOutput(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
        if (target == ToneEngine.TARGET_EARPIECE && earpiece == null) {
            target = ToneEngine.TARGET_SPEAKER;
        }
        forceSpeaker = Prefs.forceSpeaker(this);
        maxVolume = Prefs.maxVolume(this);
        sForced = false;
        sRoutedType = -1;

        requestFocus(target);

        AudioDeviceInfo preferred;
        if (target == ToneEngine.TARGET_EARPIECE) {
            router.engageEarpiece();
            preferred = earpiece;
            if (maxVolume) {
                savedCall = raiseToMax(AudioManager.STREAM_VOICE_CALL, savedCall);
            }
        } else {
            preferred = AudioRouter.findOutput(am, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            // Android 6 cannot report the routed device: decide from the device list.
            if (forceSpeaker && Build.VERSION.SDK_INT < Build.VERSION_CODES.N
                    && !AudioRouter.findHeadphones(am, true).isEmpty()) {
                forceRouter();
            }
            if (maxVolume) {
                savedMusic = raiseToMax(AudioManager.STREAM_MUSIC, savedMusic);
            }
        }

        engine = new ToneEngine(Prefs.mode(this), Prefs.duration(this), target,
                Prefs.frequency(this), Prefs.wave(this), this);
        if (!engine.start(preferred)) {
            engine = null;
            cleanup();
            return false;
        }
        sEngine = engine;
        sRunning = true;
        sLastCompleted = false;
        startVibration();
        handler.postDelayed(routingCheck, FIRST_ROUTING_CHECK_MS);
        handler.postDelayed(notificationTick, NOTIFICATION_TICK_MS);
        dispatch();
        return true;
    }

    private void checkRouting() {
        if (engine == null) {
            return;
        }
        AudioDeviceInfo routed = engine.getRoutedDevice();
        sRoutedType = routed != null ? routed.getType() : -1;
        if (engine.getTarget() != ToneEngine.TARGET_SPEAKER || !forceSpeaker) {
            return;
        }
        int method = router.getActiveMethod();
        if (method == AudioRouter.METHOD_NONE) {
            boolean wrongOutput = routed != null
                    ? routed.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    : !AudioRouter.findHeadphones(am, true).isEmpty();
            if (wrongOutput) {
                forceRouter();
            }
        } else {
            router.engage(AudioRouter.METHOD_AUTO); // idempotent re-apply
        }
    }

    /** Same technique as "Kulaklık Kapat": force everything to the built-in speaker. */
    private void forceRouter() {
        router.engage(AudioRouter.METHOD_AUTO);
        sForced = true;
        if (maxVolume && router.getActiveMethod() == AudioRouter.METHOD_COMMUNICATION) {
            // In communication mode media volume is capped by the call volume.
            savedCall = raiseToMax(AudioManager.STREAM_VOICE_CALL, savedCall);
        }
    }

    @Override
    public void onEngineFinished(boolean completed) {
        handler.post(() -> finish(completed));
    }

    private void finish(boolean completed) {
        if (engine == null) {
            return;
        }
        engine = null;
        sLastCompleted = completed;
        cleanup();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void cleanup() {
        handler.removeCallbacks(routingCheck);
        handler.removeCallbacks(notificationTick);
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        router.release();
        restoreVolumes();
        abandonFocus();
        boolean wasRunning = sRunning;
        sEngine = null;
        sRunning = false;
        sForced = false;
        if (wasRunning) {
            dispatch();
        }
    }

    private void dispatch() {
        for (Listener l : LISTENERS) {
            l.onCleanStateChanged();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Volume, focus, vibration
    // ---------------------------------------------------------------------------------------

    /** Raises the stream to max and returns the volume to restore later. */
    private int raiseToMax(int stream, int alreadySaved) {
        int saved = alreadySaved >= 0 ? alreadySaved : am.getStreamVolume(stream);
        try {
            am.setStreamVolume(stream, am.getStreamMaxVolume(stream), 0);
        } catch (RuntimeException ignored) {
            // e.g. Do Not Disturb restrictions
        }
        return saved;
    }

    private void restoreVolumes() {
        try {
            if (savedMusic >= 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusic, 0);
            }
            if (savedCall >= 0) {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedCall, 0);
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
        savedMusic = -1;
        savedCall = -1;
    }

    @SuppressWarnings("deprecation")
    private void requestFocus(int target) {
        boolean earpiece = target == ToneEngine.TARGET_EARPIECE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(earpiece ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                                    : AudioAttributes.USAGE_MEDIA)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener, handler)
                    .build();
            am.requestAudioFocus(req);
            focusRequest = req;
        } else {
            am.requestAudioFocus(focusListener,
                    earpiece ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            focusRequest = focusListener;
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonFocus() {
        if (focusRequest == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest instanceof AudioFocusRequest) {
            am.abandonAudioFocusRequest((AudioFocusRequest) focusRequest);
        } else {
            am.abandonAudioFocus(focusListener);
        }
        focusRequest = null;
    }

    @SuppressWarnings("deprecation")
    private void startVibration() {
        if (!Prefs.vibrate(this)) {
            return;
        }
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0));
            } else {
                v.vibrate(VIBRATION_PATTERN, 0);
            }
            vibrator = v;
        } catch (RuntimeException ignored) {
            // vibration is optional
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------

    private boolean goForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CleanService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(getString(R.string.notif_title))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(getColor(R.color.primary))
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build());
        ToneEngine e = engine;
        if (e != null) {
            long remaining = Math.max(0, e.getTotalMs() - e.getElapsedMs());
            b.setContentText(getString(R.string.notif_text,
                    Math.round(e.getCurrentFreq()), Format.time(remaining)));
            b.setProgress(1000, Math.round(e.getProgress() * 1000), false);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }
}
