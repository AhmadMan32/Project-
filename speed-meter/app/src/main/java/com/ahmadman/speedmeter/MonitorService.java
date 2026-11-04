package com.ahmadman.speedmeter;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.ahmadman.speedmeter.core.SpeedFormat;
import com.ahmadman.speedmeter.core.SpeedSampler;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps the floating meter on screen and the speed in the status bar. */
public class MonitorService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_STOP = "com.ahmadman.speedmeter.STOP";

    private static final String CHANNEL = "meter";
    private static final int NOTIFICATION_ID = 1;

    /** Shared with the app screen for session totals. */
    static final SpeedSampler SAMPLER = new SpeedSampler();

    interface Listener {
        void onMonitorChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean running;
    private static volatile boolean overlayShown;

    static boolean isRunning() {
        return running;
    }

    static boolean isOverlayShown() {
        return overlayShown;
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    static void stop(Context c) {
        Style.prefs(c).edit().putBoolean("wasRunning", false).apply();
        c.stopService(new Intent(c, MonitorService.class));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SpeedView view;
    private WindowManager.LayoutParams lp;
    private Style style;
    private boolean ticking;
    private long lastTextUpdate;
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            SAMPLER.sample(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), SystemClock.elapsedRealtime());
            if (view != null) {
                view.setSpeeds(SAMPLER.download(), SAMPLER.upload(), SAMPLER.isSupported());
            }
            updateNotification(false);
            for (Listener l : LISTENERS) {
                l.onMonitorChanged();
            }
            if (ticking) {
                handler.postDelayed(this, style.intervalMs);
            }
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                pause(); // nothing to show: save battery
            } else {
                resume();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        style = Style.load(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Style.prefs(this).registerOnSharedPreferenceChangeListener(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screen, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Style.prefs(this).edit().putBoolean("wasRunning", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        Style.prefs(this).edit().putBoolean("wasRunning", true).apply();
        showOverlay();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isInteractive()) {
            resume();
        }
        notifyListeners();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        pause();
        removeOverlay();
        Style.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        try {
            unregisterReceiver(screen);
        } catch (RuntimeException ignored) {
            // not registered
        }
        running = false;
        notifyListeners();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyListeners() {
        for (Listener l : LISTENERS) {
            l.onMonitorChanged();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MeterTileService.requestUpdate(this);
        }
    }

    private void resume() {
        if (ticking || !running) {
            return;
        }
        ticking = true;
        SAMPLER.restart();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void pause() {
        ticking = false;
        handler.removeCallbacks(tick);
    }

    // ---------------------------------------------------------------------------------------
    // Overlay
    // ---------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void showOverlay() {
        if (view != null || !Settings.canDrawOverlays(this)) {
            overlayShown = view != null;
            return;
        }
        view = new SpeedView(this);
        view.setOpacityOnView(false);
        view.setStyle(style);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type, flags(), PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        place();
        installDrag();
        try {
            wm.addView(view, lp);
            overlayShown = true;
        } catch (RuntimeException e) {
            // permission revoked meanwhile: keep the status bar part
            view = null;
            overlayShown = false;
        }
    }

    private int flags() {
        int f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (style.locked) {
            f |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; // taps reach the app below
        }
        return f;
    }

    /** Window opacity. Android 12+ blocks touches passing through overlays above 80 %. */
    private float windowAlpha() {
        float a = style.opacity / 100f;
        if (style.locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            a = Math.min(a, 0.8f);
        }
        return a;
    }

    private void place() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int statusBar = statusBarHeight();
        int x = style.x >= 0 ? style.x : Math.round(16 * dm.density);
        int y = style.y >= 0 ? style.y : statusBar;
        view.measure(0, 0);
        lp.x = Math.max(0, Math.min(x, dm.widthPixels - Math.max(1, view.getMeasuredWidth())));
        lp.y = Math.max(0, Math.min(y, dm.heightPixels - Math.max(1, view.getMeasuredHeight())));
        lp.alpha = windowAlpha();
        lp.flags = flags();
    }

    int statusBarHeight() {
        @SuppressLint({"InternalInsetResource", "DiscouragedApi"})
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : Math.round(24 * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void installDrag() {
        final float[] start = new float[4];
        view.setOnTouchListener((v, e) -> {
            if (style.locked) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = lp.x;
                    start[3] = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = Math.max(0, Math.round(start[2] + e.getRawX() - start[0]));
                    lp.y = Math.max(0, Math.round(start[3] + e.getRawY() - start[1]));
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (RuntimeException ignored) {
                        // view gone
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    style.x = lp.x;
                    style.y = lp.y;
                    Style.prefs(this).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                    return true;
                default:
                    return false;
            }
        });
    }

    private void removeOverlay() {
        if (view != null) {
            try {
                wm.removeView(view);
            } catch (RuntimeException ignored) {
                // already removed
            }
            view = null;
        }
        overlayShown = false;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("wasRunning".equals(key) || "askedNotifications".equals(key)) {
            return;
        }
        Style old = style;
        style = Style.load(this);
        if (view == null) {
            showOverlay();
        } else {
            view.setStyle(style);
            boolean moved = "x".equals(key) || "y".equals(key);
            // positions written by our own drag are already applied
            if (!moved || old.x != style.x || old.y != style.y) {
                place();
                try {
                    wm.updateViewLayout(view, lp);
                } catch (RuntimeException ignored) {
                    // view gone
                }
            }
        }
        if (ticking) {
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, style.intervalMs);
        }
        updateNotification(true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null) {
            place(); // keep it on screen after rotation
            try {
                wm.updateViewLayout(view, lp);
            } catch (RuntimeException ignored) {
                // view gone
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notification with the speed drawn into the status bar icon
    // ---------------------------------------------------------------------------------------

    private void updateNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && !style.statusIcon && now - lastTextUpdate < 3000) {
            return; // text only: no need to refresh every second
        }
        lastTextUpdate = now;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Locale loc = Locale.getDefault();
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (style.statusIcon && SAMPLER.isSupported()) {
            b.setSmallIcon(Icon.createWithBitmap(speedIcon(SAMPLER.download())));
        } else {
            b.setSmallIcon(R.drawable.ic_notification);
        }
        String title = "↓ " + SpeedFormat.format(SAMPLER.download(), style.bits, loc)
                + "    ↑ " + SpeedFormat.format(SAMPLER.upload(), style.bits, loc);
        String text = getString(R.string.notif_totals, SpeedFormat.size(SAMPLER.totalDownload(), loc),
                SpeedFormat.size(SAMPLER.totalUpload(), loc));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class)
                .setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    /** Two lines in the status bar icon: "2.3" over "Mbps". */
    private Bitmap speedIcon(double bytesPerSec) {
        int size = Math.round(24 * getResources().getDisplayMetrics().density);
        // a new bitmap each time: the notification system keeps a reference to the last one
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String[] parts = SpeedFormat.parts(bytesPerSec, style.bits, Locale.getDefault());
        iconPaint.setColor(Color.WHITE);
        iconPaint.setTextAlign(Paint.Align.CENTER);
        iconPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        fit(parts[0], size * 0.62f, size);
        c.drawText(parts[0], size / 2f, size * 0.56f, iconPaint);
        iconPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        fit(parts[1], size * 0.40f, size);
        c.drawText(parts[1], size / 2f, size * 0.96f, iconPaint);
        return bmp;
    }

    private void fit(String s, float maxTextSize, int width) {
        iconPaint.setTextSize(maxTextSize);
        float w = iconPaint.measureText(s);
        if (w > width) {
            iconPaint.setTextSize(maxTextSize * width / w);
        }
    }
}
