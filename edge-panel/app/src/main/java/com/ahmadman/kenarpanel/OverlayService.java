package com.ahmadman.kenarpanel;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.Collections;

/**
 * Keeps the edge handle on screen above every app and opens the panel the moment the handle
 * is tapped or swiped inwards. Dragging the handle up or down moves it.
 */
public class OverlayService extends Service implements Panel.Host {

    static final String CHANNEL = "panel";
    static final int ID = 1;
    static final String ACTION_STOP = "com.ahmadman.kenarpanel.STOP";

    private static volatile OverlayService running;

    static void start(Context c) {
        if (!Settings.canDrawOverlays(c)) {
            return;
        }
        Intent i = new Intent(c, OverlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("KenarPanel", "start", e);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, OverlayService.class));
    }

    static boolean isRunning() {
        return running != null;
    }

    static OverlayService instance() {
        return running;
    }

    /** Settings changed: re-apply them to the handle and the panel. */
    static void settingsChanged() {
        OverlayService s = running;
        if (s != null) {
            s.applyHandle();
            s.panel.refresh();
        }
    }

    private WindowManager wm;
    private Prefs prefs;
    private Remote remote;
    private HandleView handle;
    private WindowManager.LayoutParams handleLp;
    Panel panel;
    private boolean panelShown;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannel(this);
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID, n);
            }
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = getSystemService(WindowManager.class);
        remote = new Remote(this);
        panel = new Panel(this, remote, this); // built now so opening is instant
        handle = new HandleView(this);
        handle.setOnTouchListener(new HandleTouch());
        handleLp = new WindowManager.LayoutParams(1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            handleLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        applyHandle();
        try {
            wm.addView(handle, handleLp);
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }
        running = this;
        MainActivity.notifyStateChanged();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.enabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        closePanel();
        if (handle != null && handle.isAttachedToWindow()) {
            wm.removeView(handle);
        }
        if (remote != null) {
            remote.shutdown();
        }
        MainActivity.notifyStateChanged();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyHandle(); // rotation: keep the same relative position
    }

    @SuppressWarnings("deprecation")
    static int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int screenHeight() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.heightPixels;
    }

    WindowManager.LayoutParams handleParams() {
        return handleLp;
    }

    HandleView handleView() {
        return handle;
    }

    boolean panelShown() {
        return panelShown;
    }

    @SuppressLint("RtlHardcoded") // the physical screen edge, whatever the language direction
    void applyHandle() {
        if (handle == null) {
            return;
        }
        boolean right = prefs.right();
        int length = Math.round(dp(prefs.lengthDp()));
        float thickness = dp(prefs.thicknessDp());
        handleLp.width = Math.round(thickness + dp(18)); // wider touch area than the visible bar
        handleLp.height = length;
        handleLp.gravity = Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT);
        handleLp.x = 0;
        handleLp.y = Math.round(prefs.position() * Math.max(0, screenHeight() - length));
        handle.style(right, thickness, prefs.opacity());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // keep the system back gesture from taking the swipe
            handle.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, handleLp.width, length)));
        }
        if (handle.isAttachedToWindow()) {
            wm.updateViewLayout(handle, handleLp);
        }
    }

    /** Tap or inward swipe opens; vertical drag moves the handle. */
    private final class HandleTouch implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startY;
        private boolean dragging;
        private boolean opened;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startY = handleLp.y;
                    dragging = false;
                    opened = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = prefs.right() ? downX - e.getRawX() : e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (!dragging && !opened && dx > dp(20) && dx > Math.abs(dy)) {
                        opened = true;
                        openPanel();
                        return true;
                    }
                    if (!dragging && !opened && !prefs.locked() && Math.abs(dy) > slop * 1.5f && Math.abs(dy) > dx) {
                        dragging = true;
                    }
                    if (dragging) {
                        int max = Math.max(0, screenHeight() - handleLp.height);
                        handleLp.y = Math.max(0, Math.min(max, Math.round(startY + dy)));
                        wm.updateViewLayout(handle, handleLp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int max = Math.max(1, screenHeight() - handleLp.height);
                        prefs.position(handleLp.y / (float) max);
                    } else if (!opened) {
                        float dist = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (dist < slop * 2) {
                            v.performClick();
                            openPanel();
                        }
                    }
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    void openPanel() {
        if (panelShown) {
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        panel.fit(); // screen width may have changed (rotation)
        try {
            wm.addView(panel.root, lp);
            panelShown = true;
            handle.setVisibility(View.INVISIBLE);
        } catch (RuntimeException e) {
            android.util.Log.w("KenarPanel", "panel", e);
        }
    }

    @Override
    public void closePanel() {
        if (!panelShown) {
            return;
        }
        panelShown = false;
        remote.release();
        try {
            wm.removeView(panel.root);
        } catch (RuntimeException ignored) {
            // already gone
        }
        if (handle != null) {
            handle.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void openApp(String pkg) {
        Intent i = Panel.launch(this, pkg);
        closePanel();
        if (i != null) {
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                android.util.Log.w("KenarPanel", "launch " + pkg, e);
            }
        }
    }

    @Override
    public void openSettings(boolean pickApps) {
        closePanel();
        Intent i = new Intent(this, pickApps ? AppPickerActivity.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
            // refused from the background
        }
    }

    private Notification notification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : legacyBuilder();
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder legacyBuilder() {
        return new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
    }

    static void createChannel(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel),
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
