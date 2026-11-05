package com.ahmadman.kenarpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.kenarpanel.core.Remotes;

import java.util.List;

/** Start/stop, handle position and look, panel apps, TV brand and permissions. */
public class MainActivity extends Activity {

    private static MainActivity visible;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Remote remote;
    private boolean waitingForOverlay;
    private TextView[] brandViews;

    static void notifyStateChanged() {
        MainActivity a = visible;
        if (a != null) {
            a.handler.post(a::render);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));
        prefs = new Prefs(this);
        remote = new Remote(this);

        findViewById(R.id.toggle).setOnClickListener(v -> toggle());
        segment(R.id.seg_side, new int[]{R.string.side_left, R.string.side_right}, prefs.right() ? 1 : 0, i -> {
            prefs.right(i == 1);
            changed();
        });
        seek(R.id.length, R.id.length_label, R.string.length_value, 60, 200, prefs.lengthDp(), v -> prefs.lengthDp(v));
        seek(R.id.thickness, R.id.thickness_label, R.string.thickness_value, 3, 12, prefs.thicknessDp(),
                v -> prefs.thicknessDp(v));
        seek(R.id.opacity, R.id.opacity_label, R.string.opacity_value, 20, 100, prefs.opacity(), v -> prefs.opacity(v));
        bind(R.id.opt_locked, prefs.locked(), on -> prefs.locked(on));
        bind(R.id.opt_haptics, prefs.haptics(), on -> prefs.haptics(on));
        bind(R.id.opt_digits, prefs.digits(), on -> prefs.digits(on));
        bind(R.id.opt_boot, prefs.boot(), on -> prefs.boot(on));
        findViewById(R.id.reset_position).setOnClickListener(v -> {
            prefs.position(0.32f);
            changed();
            Toast.makeText(this, R.string.position_reset, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.choose_apps).setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));

        Remotes.Brand[] brands = Remotes.ALL;
        int current = 0;
        for (int i = 0; i < brands.length; i++) {
            if (brands[i].id.equals(prefs.brand())) {
                current = i;
            }
        }
        brandViews = segment(R.id.seg_brand, null, current, i -> {
            prefs.brand(Remotes.ALL[i].id);
            changed();
        });
        findViewById(R.id.test_power).setOnClickListener(v -> {
            if (!remote.press(Remotes.byId(prefs.brand()), Remotes.Key.POWER)) {
                Toast.makeText(this, R.string.ir_missing, Toast.LENGTH_SHORT).show();
            }
            remote.release();
        });
        findViewById(R.id.find_brand).setOnClickListener(v -> findBrand());
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = this;
        if (waitingForOverlay && Perms.overlay(this)) {
            waitingForOverlay = false;
            start();
        }
        render();
    }

    @Override
    protected void onPause() {
        visible = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        remote.shutdown();
        super.onDestroy();
    }

    private void toggle() {
        if (OverlayService.isRunning()) {
            prefs.enabled(false);
            OverlayService.stop(this);
            handler.postDelayed(this::render, 200);
            return;
        }
        if (!Perms.overlay(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_overlay)
                    .setMessage(R.string.overlay_explain)
                    .setPositiveButton(R.string.allow, (d, w) -> {
                        waitingForOverlay = true;
                        Perms.openOverlay(this);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        start();
    }

    private void start() {
        prefs.enabled(true);
        if (!Perms.notifications(this)) {
            Perms.openNotifications(this);
        }
        OverlayService.start(this);
        handler.postDelayed(this::render, 300);
    }

    private void changed() {
        OverlayService.settingsChanged();
        render();
    }

    private void render() {
        boolean on = OverlayService.isRunning();
        ((TextView) findViewById(R.id.state_title)).setText(on ? R.string.state_on : R.string.state_off);
        ((TextView) findViewById(R.id.state_text)).setText(on ? R.string.state_on_text : R.string.state_off_text);
        TextView toggle = findViewById(R.id.toggle);
        toggle.setText(on ? R.string.stop : R.string.start);

        // apps preview
        LinearLayout row = findViewById(R.id.apps_preview);
        row.removeAllViews();
        List<String> apps = Panel.appsOrDefault(this, prefs);
        PackageManager pm = getPackageManager();
        for (String pkg : apps) {
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                ImageView iv = (ImageView) LayoutInflater.from(this).inflate(R.layout.item_app_small, row, false);
                iv.setImageDrawable(Panel.icon(pm, pkg));
                iv.setContentDescription(label);
                row.addView(iv);
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled
            }
        }
        ((TextView) findViewById(R.id.apps_count)).setText(getString(R.string.apps_count, row.getChildCount(),
                Prefs.MAX_APPS));

        boolean ir = remote.available();
        ImageView irIcon = findViewById(R.id.ir_icon);
        irIcon.setImageResource(ir ? R.drawable.ic_check : R.drawable.ic_warning);
        irIcon.setColorFilter(getColor(ir ? R.color.ok : R.color.warn));
        ((TextView) findViewById(R.id.ir_status)).setText(ir ? R.string.ir_ok : R.string.ir_missing);
        renderPermissions();
    }

    private void findBrand() {
        if (!remote.available()) {
            Toast.makeText(this, R.string.ir_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        final int[] index = {-1};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.find_title)
                .setMessage(R.string.find_text)
                .setPositiveButton(R.string.find_worked, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        Runnable next = new Runnable() {
            @Override
            public void run() {
                if (!d.isShowing()) {
                    return;
                }
                index[0] = (index[0] + 1) % Remotes.ALL.length;
                Remotes.Brand b = Remotes.ALL[index[0]];
                d.setMessage(getString(R.string.find_trying, b.name));
                remote.press(b, Remotes.Key.POWER);
                remote.release();
                handler.postDelayed(this, 3000);
            }
        };
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (index[0] >= 0) {
                Remotes.Brand b = Remotes.ALL[index[0]];
                prefs.brand(b.id);
                for (int i = 0; i < brandViews.length; i++) {
                    brandViews[i].setSelected(i == index[0]);
                }
                changed();
                Toast.makeText(this, getString(R.string.find_saved, b.name), Toast.LENGTH_LONG).show();
            }
            d.dismiss();
        }));
        d.setOnDismissListener(x -> handler.removeCallbacks(next));
        d.show();
        handler.post(next);
    }

    // ---------------------------------------------------------------------------------------

    private void renderPermissions() {
        LinearLayout box = findViewById(R.id.permissions);
        box.removeAllViews();
        permRow(box, R.string.perm_overlay, R.string.perm_overlay_desc, Perms.overlay(this), () -> Perms.openOverlay(this));
        permRow(box, R.string.perm_notifications, R.string.perm_notifications_desc, Perms.notifications(this),
                () -> Perms.openNotifications(this));
        permRow(box, R.string.perm_battery, R.string.perm_battery_desc, Perms.battery(this), () -> Perms.openBattery(this));
        if (Perms.isXiaomi()) {
            permRow(box, R.string.perm_xiaomi, R.string.perm_xiaomi_desc, null, () -> Perms.openXiaomi(this));
        }
    }

    private void permRow(LinearLayout box, int title, int desc, Boolean ok, Runnable action) {
        View r = LayoutInflater.from(this).inflate(R.layout.item_permission, box, false);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        ((TextView) r.findViewById(R.id.desc)).setText(desc);
        ImageView icon = r.findViewById(R.id.icon);
        if (ok == null) {
            icon.setImageResource(R.drawable.ic_info);
            icon.setColorFilter(getColor(R.color.text_secondary));
        } else {
            icon.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_warning);
            icon.setColorFilter(getColor(ok ? R.color.ok : R.color.warn));
        }
        icon.setContentDescription(getString(ok == null ? R.string.perm_info : ok ? R.string.perm_ok : R.string.perm_missing));
        r.setOnClickListener(v -> action.run());
        box.addView(r);
    }

    interface IntSetter {
        void set(int v);
    }

    interface BoolSetter {
        void set(boolean v);
    }

    /** @param labels null = brand names */
    private TextView[] segment(int id, int[] labels, int selected, IntSetter set) {
        LinearLayout box = findViewById(id);
        int n = labels != null ? labels.length : Remotes.ALL.length;
        TextView[] views = new TextView[n];
        for (int i = 0; i < n; i++) {
            TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_segment, box, false);
            if (labels != null) {
                t.setText(labels[i]);
            } else {
                t.setText(Remotes.ALL[i].name);
            }
            final int index = i;
            t.setOnClickListener(v -> {
                for (int k = 0; k < views.length; k++) {
                    views[k].setSelected(k == index);
                }
                set.set(index);
            });
            t.setSelected(i == selected);
            views[i] = t;
            box.addView(t);
        }
        return views;
    }

    private void seek(int id, int labelId, int format, int min, int max, int value, IntSetter set) {
        SeekBar s = findViewById(id);
        TextView label = findViewById(labelId);
        s.setMax(max - min);
        s.setProgress(value - min);
        label.setText(getString(format, value));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (fromUser) {
                    set.set(min + p);
                    label.setText(getString(format, min + p));
                    OverlayService.settingsChanged();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
    }

    private void bind(int id, boolean value, BoolSetter set) {
        Switch s = findViewById(id);
        s.setChecked(value);
        s.setOnCheckedChangeListener((b, on) -> {
            set.set(on);
            OverlayService.settingsChanged();
        });
    }

    @SuppressWarnings("deprecation")
    static void applyInsets(View root) {
        final int l = root.getPaddingLeft();
        final int t = root.getPaddingTop();
        final int r = root.getPaddingRight();
        final int b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int il;
            int it;
            int ir;
            int ib;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
                il = i.left;
                it = i.top;
                ir = i.right;
                ib = i.bottom;
            } else {
                il = insets.getSystemWindowInsetLeft();
                it = insets.getSystemWindowInsetTop();
                ir = insets.getSystemWindowInsetRight();
                ib = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return insets;
        });
    }
}
