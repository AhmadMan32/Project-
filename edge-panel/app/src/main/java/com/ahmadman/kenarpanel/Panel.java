package com.ahmadman.kenarpanel;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.ahmadman.kenarpanel.core.Motion;
import com.ahmadman.kenarpanel.core.Remotes;

import java.util.ArrayList;
import java.util.List;

/**
 * The panel: app shortcuts and the LG remote (power, HDMI 1, HDMI 2). Built once and kept in
 * memory. Everything on it is driven by one number, the open progress (0 = hidden at the edge,
 * 1 = open), so it can follow the finger while pulling and animate the same way on its own.
 */
final class Panel {

    interface Host {
        void openApp(String pkg);

        void openSettings(boolean pickApps);

        /** Back key or a tap on the dimmed area. */
        void requestClose();

        /** Background blur, 0..1 of the full radius. */
        void blur(float fraction);

        /** The edge handle fades out while the panel comes in. */
        void handleAlpha(float alpha);
    }

    static final String[] DEFAULT_APPS = {"com.whatsapp", "com.google.android.youtube", "com.instagram.android",
            "com.android.chrome", "org.telegram.messenger", "com.spotify.music", "com.netflix.mediaclient"};

    private final Context context;
    private final Remote remote;
    private final Host host;
    private final Prefs prefs;
    final PanelRoot root;
    private final LinearLayout content;
    private final View appsCard;
    private final View remoteCard;
    private final Drawable dim;
    private final List<View> cascade = new ArrayList<>();
    private final float dp;
    private boolean right;
    private int travel = 1;
    private float progress;
    private Tween tween;

    @SuppressLint("InflateParams") // window root
    Panel(Context c, Remote remote, Host host) {
        this.context = c;
        this.remote = remote;
        this.host = host;
        this.prefs = new Prefs(c);
        dp = c.getResources().getDisplayMetrics().density;
        root = (PanelRoot) LayoutInflater.from(c).inflate(R.layout.panel, null);
        root.setOnClose(host::requestClose);
        dim = root.getBackground().mutate();
        content = root.findViewById(R.id.content);
        appsCard = root.findViewById(R.id.apps_card);
        remoteCard = root.findViewById(R.id.remote_card);
        key(R.id.k_power, Remotes.Key.POWER);
        key(R.id.k_hdmi1, Remotes.Key.HDMI1);
        key(R.id.k_hdmi2, Remotes.Key.HDMI2);
        root.findViewById(R.id.settings).setOnClickListener(v -> host.openSettings(false));
        refresh();
    }

    /** Re-reads settings (side, apps); called when they change, not on open. */
    void refresh() {
        right = prefs.right();
        content.removeAllViews();
        content.addView(right ? remoteCard : appsCard);
        content.addView(right ? appsCard : remoteCard);
        LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) remoteCard.getLayoutParams();
        int g = Math.round(10 * dp);
        gap.setMarginStart(right ? 0 : g);
        gap.setMarginEnd(right ? g : 0);
        remoteCard.setLayoutParams(gap);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        lp.gravity = (right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        content.setLayoutParams(lp);
        loadApps();
        boolean ir = remote.available();
        root.findViewById(R.id.ir_missing).setVisibility(ir ? View.GONE : View.VISIBLE);
        for (int id : new int[]{R.id.k_power, R.id.k_hdmi1, R.id.k_hdmi2}) {
            View v = root.findViewById(id);
            v.setEnabled(ir);
            v.setAlpha(ir ? 1f : 0.35f);
        }
        collectCascade();
        fit();
        setProgress(progress);
    }

    /** Things that slide in one after the other: app icons, then the remote's rows. */
    private void collectCascade() {
        cascade.clear();
        LinearLayout apps = root.findViewById(R.id.apps);
        List<View> remoteRows = new ArrayList<>();
        remoteRows.add(root.findViewById(R.id.remote_header));
        remoteRows.add(root.findViewById(R.id.k_power));
        remoteRows.add(root.findViewById(R.id.power_label));
        remoteRows.add(root.findViewById(R.id.hdmi_row));
        List<View> icons = new ArrayList<>();
        for (int i = 0; i < apps.getChildCount(); i++) {
            icons.add(apps.getChildAt(i));
        }
        // in the order they come into view: the remote card leads the slide on either side
        cascade.addAll(remoteRows);
        cascade.addAll(icons);
    }

    /** Sizes the remote to the screen and measures how far the panel travels. */
    void fit() {
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Math.round(236 * dp), screenW - Math.round((72 + 10 + 24 + 6) * dp));
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) remoteCard.getLayoutParams();
        if (rl.width != width) {
            rl.width = width;
            remoteCard.setLayoutParams(rl);
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        travel = Math.max(1, content.getMeasuredWidth());
    }

    /** How far the finger has to pull for the panel to be fully open, in pixels. */
    int travel() {
        return travel;
    }

    float progress() {
        return progress;
    }

    /** Places everything for this open progress (may go slightly above 1 while bouncing). */
    void setProgress(float p) {
        progress = p;
        float c = Motion.clamp(p);
        float off = (1f - p) * travel;
        content.setTranslationX(right ? off : -off);
        dim.setAlpha(Math.round(255 * c));
        host.blur(c);
        host.handleAlpha(1f - Motion.clamp(c * 3f));
        float slide = 18 * dp * (right ? 1 : -1);
        int n = cascade.size();
        for (int i = 0; i < n; i++) {
            float q = Motion.cascade(c, i, n);
            View v = cascade.get(i);
            v.setAlpha(v.isEnabled() || v.getId() == R.id.hdmi_row ? q : q * 0.35f);
            v.setTranslationX((1f - q) * slide);
        }
    }

    boolean animating() {
        return tween != null && tween.running();
    }

    void cancelAnimation() {
        if (tween != null) {
            tween.cancel();
            tween = null;
        }
    }

    /** Opens with a spring (a small bounce) or closes quickly, from wherever the panel is now. */
    void animateTo(float target, Motion.Speed speed, Runnable end) {
        cancelAnimation();
        if (speed == Motion.Speed.OFF || Math.abs(progress - target) < 0.001f) {
            setProgress(target);
            if (end != null) {
                end.run();
            }
            return;
        }
        boolean opening = target > progress;
        float distance = Math.max(0.35f, Math.abs(target - progress));
        // the spring's motion is mostly in its first half: it feels as fast as the setting
        long millis = Math.round(speed.millis * distance * (opening ? 2.0f : 0.9f));
        tween = Tween.run(progress, target, millis, opening ? Motion::spring : f -> f * f, this::setProgress, end);
    }

    static List<String> appsOrDefault(Context c, Prefs prefs) {
        if (prefs.appsChosen()) {
            return prefs.apps();
        }
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String p : DEFAULT_APPS) {
            if (pm.getLaunchIntentForPackage(p) != null) {
                out.add(p);
            }
        }
        return out;
    }

    private void loadApps() {
        LinearLayout box = root.findViewById(R.id.apps);
        box.removeAllViews();
        PackageManager pm = context.getPackageManager();
        LayoutInflater inf = LayoutInflater.from(context);
        for (String pkg : appsOrDefault(context, prefs)) {
            CharSequence label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                continue; // uninstalled
            }
            ImageView v = (ImageView) inf.inflate(R.layout.item_app, box, false);
            v.setImageDrawable(icon(pm, pkg));
            v.setContentDescription(label);
            v.setOnClickListener(x -> host.openApp(pkg));
            box.addView(v);
        }
        ImageView add = (ImageView) inf.inflate(R.layout.item_app, box, false);
        add.setImageResource(R.drawable.ic_add);
        add.setBackgroundResource(R.drawable.bg_key);
        int pad = Math.round(14 * dp);
        add.setPadding(pad, pad, pad, pad);
        add.setContentDescription(context.getString(R.string.apps_add));
        add.setOnClickListener(x -> host.openSettings(true));
        box.addView(add);
    }

    /** Keys act on touch-down (no waiting); they squeeze, bounce back and ripple from the finger. */
    @SuppressLint("ClickableViewAccessibility") // click is wired too, for accessibility
    private void key(int id, Remotes.Key key) {
        View v = root.findViewById(id);
        v.setOnTouchListener((view, e) -> {
            Motion.Speed speed = prefs.speed();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.drawableHotspotChanged(e.getX(), e.getY()); // ripple starts under the finger
                    view.setPressed(true);
                    remote.press(key);
                    if (prefs.haptics()) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    if (speed != Motion.Speed.OFF) {
                        scale(view, 0.9f, 70, new DecelerateInterpolator());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    if (speed == Motion.Speed.OFF) {
                        scale(view, 1f, 0, null);
                    } else {
                        scale(view, 1f, Math.round(speed.millis * 1.6f), new OvershootInterpolator(3f));
                    }
                    return true;
                default:
                    return true;
            }
        });
        v.setOnClickListener(view -> remote.press(key));
    }

    /** Squeeze / bounce of a key; starts on the next frame, no wait for a layout pass. */
    private static void scale(View v, float to, long millis, android.animation.TimeInterpolator interpolator) {
        Object old = v.getTag(R.id.k_hdmi1);
        if (old instanceof Tween) {
            ((Tween) old).cancel();
        }
        v.setTag(R.id.k_hdmi1, Tween.run(v.getScaleX(), to, millis, interpolator, f -> {
            v.setScaleX(f);
            v.setScaleY(f);
        }, null));
    }

    static Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    static Intent launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }
}
