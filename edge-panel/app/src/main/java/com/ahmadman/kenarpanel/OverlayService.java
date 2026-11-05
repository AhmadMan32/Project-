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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import com.ahmadman.kenarpanel.core.Motion;

import java.util.Collections;

/**
 * Keeps the edge handle on screen above every app. Pulling the handle brings the panel out
 * under the finger; letting go springs it open (or back). A tap opens it, a vertical drag
 * moves the handle. During movement the screen is asked for 90 Hz.
 */
public class OverlayService extends Service implements Panel.Host {

    static final String CHANNEL = "panel";
    static final int ID = 1;
    static final String ACTION_STOP = "com.ahmadman.kenarpanel.STOP";
    static final float WANTED_HZ = 90f;

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
    private WindowManager.LayoutParams panelLp;
    Panel panel;
    private boolean attached;
    private boolean closing;
    private float fastRate;

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
        fastRate = fastRefreshRate(this);
        remote = new Remote(this);
        panel = new Panel(this, remote, this); // built now so opening needs no loading
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
        if (panel != null) {
            panel.cancelAnimation();
        }
        detachPanel();
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

    /** Lowest refresh rate of at least 90 Hz the screen offers at its current resolution; 0 if none. */
    static float fastRefreshRate(Context c) {
        try {
            DisplayManager dm = c.getSystemService(DisplayManager.class);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode cur = d.getMode();
            Display.Mode[] modes = d.getSupportedModes();
            float[] rates = new float[modes.length];
            int n = 0;
            for (Display.Mode m : modes) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()) {
                    rates[n++] = m.getRefreshRate();
                }
            }
            return Motion.refreshRate(java.util.Arrays.copyOf(rates, n), WANTED_HZ);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Highest refresh rate of the screen (for the settings screen). */
    static float maxRefreshRate(Context c) {
        try {
            Display d = c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            float max = d.getRefreshRate();
            for (Display.Mode m : d.getSupportedModes()) {
                max = Math.max(max, m.getRefreshRate());
            }
            return max;
        } catch (RuntimeException e) {
            return 60;
        }
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

    WindowManager.LayoutParams panelParams() {
        return panelLp;
    }

    HandleView handleView() {
        return handle;
    }

    /** The panel window is on screen (also while it animates in or out). */
    boolean panelShown() {
        return attached;
    }

    /** Open, or opening: not on its way out. */
    boolean panelOpen() {
        return attached && !closing;
    }

    @SuppressLint("RtlHardcoded") // the physical screen edge, whatever the language direction
    void applyHandle() {
        if (handle == null) {
            return;
        }
        boolean right = prefs.right();
        int length = Math.round(dp(prefs.lengthDp()));
        float thickness = dp(prefs.thicknessDp());
        handleLp.width = Math.round(thickness * 1.7f + dp(14)); // room for the bar to grow while held
        handleLp.height = length;
        handleLp.gravity = Gravity.TOP | (right ? Gravity.RIGHT : Gravity.LEFT);
        handleLp.x = 0;
        handleLp.y = Math.round(prefs.position() * Math.max(0, screenHeight() - length));
        handle.style(right, thickness, prefs.opacity());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // keep the system back gesture from taking the pull
            handle.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, handleLp.width, length)));
        }
        if (handle.isAttachedToWindow()) {
            wm.updateViewLayout(handle, handleLp);
        }
    }

    /** Asks for 90 Hz on the handle window while it moves; back to normal afterwards. */
    private void handleFast(boolean fast) {
        float rate = fast ? fastRate : 0;
        if (handleLp.preferredRefreshRate != rate && handle.isAttachedToWindow()) {
            handleLp.preferredRefreshRate = rate;
            wm.updateViewLayout(handle, handleLp);
        }
    }

    /** Pull from the edge (panel follows), tap to open, vertical drag to move the handle. */
    private final class HandleTouch implements View.OnTouchListener {
        private static final int NONE = 0;
        private static final int PULL = 1;
        private static final int MOVE = 2;
        private int mode;
        private float downX;
        private float downY;
        private float pullX;
        private int startY;
        private VelocityTracker velocity;

        private float inward(float rawX, float fromX) {
            return prefs.right() ? fromX - rawX : rawX - fromX;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
            if (velocity == null) {
                velocity = VelocityTracker.obtain();
            }
            // raw coordinates: the handle window itself does not move with the pull
            MotionEvent raw = MotionEvent.obtain(e);
            raw.setLocation(e.getRawX(), e.getRawY());
            velocity.addMovement(raw);
            raw.recycle();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startY = handleLp.y;
                    mode = NONE;
                    velocity.clear();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = inward(e.getRawX(), downX);
                    float dy = e.getRawY() - downY;
                    if (mode == NONE && dx > slop && dx > Math.abs(dy)) {
                        mode = PULL;
                        pullX = e.getRawX();
                        attachPanel();
                    } else if (mode == NONE && !prefs.locked() && Math.abs(dy) > slop * 1.5f && Math.abs(dy) > dx) {
                        mode = MOVE;
                        handle.grab(true, prefs.speed().millis);
                        handleFast(true);
                    }
                    if (mode == PULL) {
                        float p = inward(e.getRawX(), pullX) / panel.travel();
                        panel.setProgress(Motion.rubberBand(p));
                    } else if (mode == MOVE) {
                        int max = Math.max(0, screenHeight() - handleLp.height);
                        handleLp.y = Math.max(0, Math.min(max, Math.round(startY + dy)));
                        wm.updateViewLayout(handle, handleLp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    float inwardDp = (prefs.right() ? -vx : vx) / getResources().getDisplayMetrics().density;
                    if (mode == PULL) {
                        if (up && Motion.settleOpen(panel.progress(), inwardDp)) {
                            settleOpen();
                        } else {
                            requestClose();
                        }
                    } else if (mode == MOVE) {
                        int max = Math.max(1, screenHeight() - handleLp.height);
                        prefs.position(handleLp.y / (float) max);
                        handle.grab(false, prefs.speed().millis * 2);
                        handle.postDelayed(() -> handleFast(false), prefs.speed().millis * 2L + 50);
                    } else if (up) {
                        float dist = Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY);
                        if (dist < slop * 2) {
                            v.performClick();
                            openPanel();
                        }
                    }
                    mode = NONE;
                    velocity.recycle();
                    velocity = null;
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    /** Puts the panel window on screen at progress 0 (hidden at the edge). */
    private void attachPanel() {
        closing = false;
        panel.cancelAnimation();
        if (attached) {
            return;
        }
        panelLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panelLp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (fastRate > 0) {
            panelLp.preferredRefreshRate = fastRate; // 90 Hz only while the panel is on screen
        }
        if ("atv".equals(prefs.tvMode()) && prefs.remoteOpen()) {
            AtvRemote.get(this).ensure(); // connects while the panel comes out: ready when it is open
        }
        panel.fit(); // screen size may have changed (rotation)
        panel.setProgress(0);
        try {
            wm.addView(panel.root, panelLp);
            attached = true;
        } catch (RuntimeException e) {
            android.util.Log.w("KenarPanel", "panel", e);
        }
    }

    private void settleOpen() {
        closing = false;
        panel.animateTo(1f, prefs.speed(), () -> handle.setVisibility(View.INVISIBLE));
    }

    void openPanel() {
        attachPanel();
        if (attached) {
            settleOpen();
        }
    }

    @Override
    public void requestClose() {
        if (!attached || closing) {
            return;
        }
        closing = true;
        handle.setVisibility(View.VISIBLE);
        panel.animateTo(0f, prefs.speed(), this::detachPanel);
    }

    private void detachPanel() {
        closing = false;
        if (!attached) {
            return;
        }
        attached = false;
        AtvRemote.get(this).idleLater();
        try {
            wm.removeView(panel.root);
        } catch (RuntimeException ignored) {
            // already gone
        }
        if (handle != null) {
            handle.setVisibility(View.VISIBLE);
            handle.setAlpha(1f);
        }
    }

    @Override
    public void handleAlpha(float alpha) {
        if (handle != null) {
            handle.setAlpha(alpha);
        }
    }

    @Override
    public void openApp(String pkg) {
        Intent i = Panel.launch(this, pkg);
        if (i != null) {
            try {
                startActivity(i);
            } catch (RuntimeException e) {
                android.util.Log.w("KenarPanel", "launch " + pkg, e);
            }
        }
        requestClose();
    }

    @Override
    public void openPair() {
        try {
            startActivity(new Intent(this, PairActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // refused from the background
        }
        requestClose();
    }

    @Override
    public void openSettings(boolean pickApps) {
        Intent i = new Intent(this, pickApps ? AppPickerActivity.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (RuntimeException ignored) {
            // refused from the background
        }
        requestClose();
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
