package com.ahmadman.podpil;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.ahmadman.podpil.core.Status;

/** The card that slides up from the bottom when the AirPods connect. */
final class Popup implements PodState.Listener {

    private static final long SHOW_MS = 12_000;
    private static Popup current;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable AUTO_HIDE = Popup::hide;

    private final Context context;
    private final WindowManager wm;
    private final View view;

    static boolean isShown() {
        return current != null;
    }

    static void show(Context c) {
        if (!Perms.overlay(c)) {
            return;
        }
        if (current == null) {
            try {
                current = new Popup(c.getApplicationContext());
            } catch (RuntimeException e) {
                android.util.Log.w("PodPil", "popup", e);
                current = null;
                return;
            }
        }
        current.onPodsChanged();
        MAIN.removeCallbacks(AUTO_HIDE);
        MAIN.postDelayed(AUTO_HIDE, SHOW_MS);
    }

    static void hide() {
        Popup p = current;
        if (p == null) {
            return;
        }
        current = null;
        MAIN.removeCallbacks(AUTO_HIDE);
        PodState.remove(p);
        p.view.animate().translationY(p.view.getHeight() + 200).alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    try {
                        p.wm.removeView(p.view);
                    } catch (RuntimeException ignored) {
                        // already gone
                    }
                }).start();
    }

    View view() {
        return view;
    }

    static Popup current() {
        return current;
    }

    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("InflateParams") // a window root has no parent
    private Popup(Context c) {
        context = c;
        wm = c.getSystemService(WindowManager.class);
        view = LayoutInflater.from(c).inflate(R.layout.popup, null);
        ((PodsView) view.findViewById(R.id.pods)).setCompact(true);
        view.findViewById(R.id.close).setOnClickListener(v -> hide());
        view.findViewById(R.id.card).setOnClickListener(v -> {
            hide();
            try {
                c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                // opening from the background was refused
            }
        });
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.y = Math.round(40 * c.getResources().getDisplayMetrics().density);
        lp.windowAnimations = android.R.style.Animation_Translucent;
        wm.addView(view, lp);
        view.setTranslationY(600);
        view.setAlpha(0f);
        view.animate().translationY(0).alpha(1f).setDuration(320).start();
        PodState.add(this);
    }

    @Override
    public void onPodsChanged() {
        Status s = PodState.status;
        String name = PodState.connectedName;
        ((TextView) view.findViewById(R.id.title)).setText(name != null ? name
                : s != null ? s.model().name : context.getString(R.string.app_name));
        ((PodsView) view.findViewById(R.id.pods)).setStatus(s);
        String line = Texts.estimateLine(context, PodState.estimate);
        TextView est = view.findViewById(R.id.estimate);
        est.setText(line);
        est.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) view.findViewById(R.id.source)).setText(s != null && s.precise() ? R.string.source_exact_short
                : R.string.source_steps_short);
    }
}
