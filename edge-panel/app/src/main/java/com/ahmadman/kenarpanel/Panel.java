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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ahmadman.kenarpanel.core.Remotes;

import java.util.ArrayList;
import java.util.List;

/**
 * The panel: app shortcuts and the TV remote. Built once and kept in memory, so opening it is
 * just adding a window: no loading.
 */
final class Panel {

    interface Host {
        void closePanel();

        void openApp(String pkg);

        void openSettings(boolean pickApps);
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
    private final List<View> keyViews = new ArrayList<>();

    @SuppressLint("InflateParams") // window root
    Panel(Context c, Remote remote, Host host) {
        this.context = c;
        this.remote = remote;
        this.host = host;
        this.prefs = new Prefs(c);
        root = (PanelRoot) LayoutInflater.from(c).inflate(R.layout.panel, null);
        root.setOnClose(host::closePanel);
        content = root.findViewById(R.id.content);
        appsCard = root.findViewById(R.id.apps_card);
        remoteCard = root.findViewById(R.id.remote_card);

        key(R.id.k_power, Remotes.Key.POWER);
        key(R.id.k_input, Remotes.Key.INPUT);
        key(R.id.k_mute, Remotes.Key.MUTE);
        key(R.id.k_vol_up, Remotes.Key.VOL_UP);
        key(R.id.k_vol_down, Remotes.Key.VOL_DOWN);
        key(R.id.k_ch_up, Remotes.Key.CH_UP);
        key(R.id.k_ch_down, Remotes.Key.CH_DOWN);
        key(R.id.k_up, Remotes.Key.UP);
        key(R.id.k_down, Remotes.Key.DOWN);
        key(R.id.k_left, Remotes.Key.LEFT);
        key(R.id.k_right, Remotes.Key.RIGHT);
        key(R.id.k_ok, Remotes.Key.OK);
        key(R.id.k_back, Remotes.Key.BACK);
        key(R.id.k_home, Remotes.Key.HOME);
        key(R.id.k_menu, Remotes.Key.MENU);
        key(R.id.k_exit, Remotes.Key.EXIT);
        int[] digitIds = {R.id.k_0, R.id.k_1, R.id.k_2, R.id.k_3, R.id.k_4, R.id.k_5, R.id.k_6, R.id.k_7, R.id.k_8,
                R.id.k_9};
        for (int d = 0; d < 10; d++) {
            key(digitIds[d], Remotes.Key.digit(d));
        }

        root.findViewById(R.id.brand).setOnClickListener(v -> {
            Remotes.Brand[] all = Remotes.ALL;
            Remotes.Brand b = brand();
            int i = 0;
            while (i < all.length && all[i] != b) {
                i++;
            }
            prefs.brand(all[(i + 1) % all.length].id);
            haptic(v);
            refreshRemote();
        });
        root.findViewById(R.id.digits_toggle).setOnClickListener(v -> {
            prefs.digits(!prefs.digits());
            refreshRemote();
        });
        root.findViewById(R.id.settings).setOnClickListener(v -> host.openSettings(false));
        refresh();
    }

    Remotes.Brand brand() {
        return Remotes.byId(prefs.brand());
    }

    /** Re-reads settings (side, apps, brand); called when they change, not on open. */
    void refresh() {
        boolean right = prefs.right();
        content.removeAllViews();
        content.addView(right ? remoteCard : appsCard);
        content.addView(right ? appsCard : remoteCard);
        LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) remoteCard.getLayoutParams();
        int g = Math.round(10 * context.getResources().getDisplayMetrics().density);
        gap.setMarginStart(right ? 0 : g);
        gap.setMarginEnd(right ? g : 0);
        remoteCard.setLayoutParams(gap);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        lp.gravity = (right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        content.setLayoutParams(lp);
        loadApps();
        refreshRemote();
        fit();
    }

    /** Sizes the remote to the screen width (phones are 320-480 dp wide); called on every open. */
    void fit() {
        float d = context.getResources().getDisplayMetrics().density;
        int screen = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(Math.round(300 * d), screen - Math.round((72 + 10 + 24 + 6) * d));
        LinearLayout.LayoutParams rl = (LinearLayout.LayoutParams) remoteCard.getLayoutParams();
        if (rl.width != width) {
            rl.width = width;
            remoteCard.setLayoutParams(rl);
        }
        // the arrow pad takes what is left between the volume and channel columns
        int pad = Math.min(Math.round(150 * d), width - Math.round((28 + 2 * 54 + 8) * d));
        View dpad = root.findViewById(R.id.dpad);
        android.view.ViewGroup.LayoutParams dl = dpad.getLayoutParams();
        if (dl.width != pad) {
            dl.width = pad;
            dl.height = pad;
            dpad.setLayoutParams(dl);
            View holder = (View) dpad.getParent();
            android.view.ViewGroup.LayoutParams hl = holder.getLayoutParams();
            hl.height = pad;
            holder.setLayoutParams(hl);
            int arrow = Math.round(pad * 0.32f);
            for (int id : new int[]{R.id.k_up, R.id.k_down, R.id.k_left, R.id.k_right}) {
                View a = root.findViewById(id);
                android.view.ViewGroup.LayoutParams al = a.getLayoutParams();
                al.width = arrow;
                al.height = arrow;
                a.setLayoutParams(al);
                int p = Math.round(arrow * 0.22f);
                a.setPadding(p, p, p, p);
            }
            View ok = root.findViewById(R.id.k_ok);
            android.view.ViewGroup.LayoutParams ol = ok.getLayoutParams();
            ol.width = Math.round(pad * 0.37f);
            ol.height = ol.width;
            ok.setLayoutParams(ol);
        }
    }

    private void refreshRemote() {
        Remotes.Brand b = brand();
        ((TextView) root.findViewById(R.id.brand)).setText(context.getString(R.string.brand_chip, b.name));
        boolean ir = remote.available();
        root.findViewById(R.id.ir_missing).setVisibility(ir ? View.GONE : View.VISIBLE);
        for (View v : keyViews) {
            Remotes.Key k = (Remotes.Key) v.getTag(R.id.k_power);
            boolean on = ir && b.has(k);
            v.setAlpha(on ? 1f : 0.3f);
            v.setEnabled(on);
        }
        boolean digits = prefs.digits();
        root.findViewById(R.id.digits).setVisibility(digits ? View.VISIBLE : View.GONE);
        ((TextView) root.findViewById(R.id.digits_toggle)).setText(digits ? R.string.digits_hide : R.string.digits_show);
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
            Drawable icon = icon(pm, pkg);
            ImageView v = (ImageView) inf.inflate(R.layout.item_app, box, false);
            v.setImageDrawable(icon);
            v.setContentDescription(label);
            v.setOnClickListener(x -> host.openApp(pkg));
            box.addView(v);
        }
        ImageView add = (ImageView) inf.inflate(R.layout.item_app, box, false);
        add.setImageResource(R.drawable.ic_add);
        add.setBackgroundResource(R.drawable.bg_key);
        int pad = Math.round(14 * context.getResources().getDisplayMetrics().density);
        add.setPadding(pad, pad, pad, pad);
        add.setContentDescription(context.getString(R.string.apps_add));
        add.setOnClickListener(x -> host.openSettings(true));
        box.addView(add);
    }

    @SuppressLint("ClickableViewAccessibility") // keys act on touch-down for zero delay; click is wired too
    private void key(int id, Remotes.Key key) {
        View v = root.findViewById(id);
        v.setTag(R.id.k_power, key);
        keyViews.add(v);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    remote.press(brand(), key);
                    haptic(view);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    remote.release();
                    return true;
                default:
                    return true;
            }
        });
        v.setOnClickListener(view -> remote.press(brand(), key)); // accessibility / keyboard
    }

    private void haptic(View v) {
        if (prefs.haptics()) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
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
