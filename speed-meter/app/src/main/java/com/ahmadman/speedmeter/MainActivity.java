package com.ahmadman.speedmeter;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.speedmeter.core.SpeedFormat;
import com.ahmadman.speedmeter.core.SpeedSampler;

import java.util.Locale;

/** Start/stop, permission status and appearance of the floating meter. */
public class MainActivity extends Activity implements MonitorService.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SpeedSampler local = new SpeedSampler();
    private Style style;
    private boolean waitingForOverlay;

    private TextView stateTitle;
    private TextView stateText;
    private TextView toggle;
    private TextView downValue;
    private TextView upValue;
    private TextView totals;
    private SpeedView preview;
    private LinearLayout permissions;

    private final Runnable localTick = new Runnable() {
        @Override
        public void run() {
            if (!MonitorService.isRunning()) {
                local.sample(TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(), SystemClock.elapsedRealtime());
                showSpeeds(local);
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));
        style = Style.load(this);

        stateTitle = findViewById(R.id.state_title);
        stateText = findViewById(R.id.state_text);
        toggle = findViewById(R.id.toggle);
        downValue = findViewById(R.id.down_value);
        upValue = findViewById(R.id.up_value);
        totals = findViewById(R.id.totals);
        preview = findViewById(R.id.preview);
        permissions = findViewById(R.id.permissions);
        toggle.setOnClickListener(v -> onToggle());

        segment(R.id.seg_content, new String[]{Style.BOTH, Style.DOWN, Style.UP, Style.TOTAL},
                new int[]{R.string.content_both, R.string.content_down, R.string.content_up, R.string.content_total},
                () -> style.content, v -> style.content = v);
        segment(R.id.seg_layout, new String[]{"row", "column"},
                new int[]{R.string.layout_row, R.string.layout_column},
                () -> style.column ? "column" : "row", v -> style.column = v.equals("column"));
        segment(R.id.seg_units, new String[]{"bits", "bytes"},
                new int[]{R.string.units_bits, R.string.units_bytes},
                () -> style.bits ? "bits" : "bytes", v -> style.bits = v.equals("bits"));
        segment(R.id.seg_size, new String[]{"10", "12", "14", "17"},
                new int[]{R.string.size_s, R.string.size_m, R.string.size_l, R.string.size_xl},
                () -> String.valueOf(style.sizeSp), v -> style.sizeSp = Integer.parseInt(v));
        segment(R.id.seg_background, new String[]{Style.BG_NONE, Style.BG_DARK, Style.BG_LIGHT},
                new int[]{R.string.bg_none, R.string.bg_dark, R.string.bg_light},
                () -> style.background, v -> style.background = v);
        segment(R.id.seg_color, new String[]{"white", "black"},
                new int[]{R.string.color_white, R.string.color_black},
                () -> style.darkText ? "black" : "white", v -> style.darkText = v.equals("black"));
        segment(R.id.seg_interval, new String[]{"500", "1000", "2000"},
                new int[]{R.string.interval_half, R.string.interval_1, R.string.interval_2},
                () -> String.valueOf(style.intervalMs), v -> style.intervalMs = Integer.parseInt(v));

        bindSwitch(R.id.opt_bars, () -> style.bars, v -> style.bars = v);
        bindSwitch(R.id.opt_locked, () -> style.locked, v -> {
            style.locked = v;
            if (!v && MonitorService.isRunning()) {
                Toast.makeText(this, R.string.drag_hint, Toast.LENGTH_LONG).show();
            }
        });
        bindSwitch(R.id.opt_status_icon, () -> style.statusIcon, v -> style.statusIcon = v);
        bindSwitch(R.id.opt_boot, () -> style.startOnBoot, v -> style.startOnBoot = v);

        SeekBar opacity = findViewById(R.id.opacity);
        TextView opacityLabel = findViewById(R.id.opacity_label);
        opacity.setMax(70);
        opacity.setProgress(style.opacity - 30);
        opacityLabel.setText(getString(R.string.opacity_value, style.opacity));
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    style.opacity = 30 + p;
                    opacityLabel.setText(getString(R.string.opacity_value, style.opacity));
                    changed();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        findViewById(R.id.reset_position).setOnClickListener(v -> {
            style.x = -1;
            style.y = -1;
            changed();
            Toast.makeText(this, R.string.position_reset, Toast.LENGTH_SHORT).show();
        });
        preview.setStyle(style);
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.addListener(this);
        local.restart();
        handler.removeCallbacks(localTick);
        handler.post(localTick);
        if (waitingForOverlay && Permissions.overlay(this)) {
            waitingForOverlay = false;
            startMeter(); // came back from the permission screen with the switch on
        }
        renderState();
        renderPermissions();
    }

    @Override
    protected void onPause() {
        MonitorService.removeListener(this);
        handler.removeCallbacks(localTick);
        super.onPause();
    }

    @Override
    public void onMonitorChanged() {
        if (MonitorService.isRunning()) {
            showSpeeds(MonitorService.SAMPLER);
        }
        renderState();
    }

    // ---------------------------------------------------------------------------------------

    private void onToggle() {
        if (MonitorService.isRunning()) {
            MonitorService.stop(this);
            renderState();
            return;
        }
        if (!Permissions.overlay(this)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_overlay)
                    .setMessage(R.string.overlay_explain)
                    .setPositiveButton(R.string.allow, (d, w) -> {
                        waitingForOverlay = true;
                        Permissions.openOverlay(this);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        startMeter();
    }

    private void startMeter() {
        if (!Permissions.notifications(this)) {
            Permissions.openNotifications(this); // asks once on Android 13+
        }
        try {
            MonitorService.start(this);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::renderState, 300);
    }

    private void renderState() {
        boolean on = MonitorService.isRunning();
        stateTitle.setText(on ? R.string.state_on : R.string.state_off);
        stateText.setText(on ? (MonitorService.isOverlayShown() ? R.string.state_on_text : R.string.state_on_no_overlay)
                : R.string.state_off_text);
        toggle.setText(on ? R.string.stop : R.string.start);
        toggle.setActivated(on);
    }

    private void showSpeeds(SpeedSampler s) {
        Locale loc = Locale.getDefault();
        String na = getString(R.string.not_supported);
        downValue.setText(s.isSupported() ? SpeedFormat.format(s.download(), style.bits, loc) : na);
        upValue.setText(s.isSupported() ? SpeedFormat.format(s.upload(), style.bits, loc) : na);
        preview.setSpeeds(s.download(), s.upload(), s.isSupported());
        if (MonitorService.isRunning()) {
            SpeedSampler m = MonitorService.SAMPLER;
            totals.setText(getString(R.string.session, SpeedFormat.size(m.totalDownload(), loc),
                    SpeedFormat.size(m.totalUpload(), loc), SpeedFormat.format(m.peakDownload(), style.bits, loc)));
            totals.setVisibility(View.VISIBLE);
        } else {
            totals.setVisibility(View.GONE);
        }
    }

    private void renderPermissions() {
        permissions.removeAllViews();
        header(R.string.perm_required);
        row(R.string.perm_overlay, R.string.perm_overlay_desc, Permissions.overlay(this), () -> Permissions.openOverlay(this));
        row(R.string.perm_notifications, R.string.perm_notifications_desc, Permissions.notifications(this),
                () -> Permissions.openNotifications(this));
        header(R.string.perm_optional);
        row(R.string.perm_battery, R.string.perm_battery_desc, Permissions.batteryUnrestricted(this),
                () -> Permissions.openBattery(this));
        if (Permissions.hibernationApplies()) {
            row(R.string.perm_hibernation, R.string.perm_hibernation_desc, Permissions.hibernationOff(this),
                    () -> Permissions.openHibernation(this));
        }
        if (Permissions.isXiaomi()) {
            // cannot be read by apps: shown as a reminder, not as a checked status
            row(R.string.perm_xiaomi, R.string.perm_xiaomi_desc, null, () -> Permissions.openXiaomi(this));
        }
    }

    private void header(int text) {
        TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_permission_header, permissions, false);
        t.setText(text);
        permissions.addView(t);
    }

    /** @param ok null = status unknown (shown as an info row) */
    private void row(int title, int desc, Boolean ok, Runnable action) {
        View r = LayoutInflater.from(this).inflate(R.layout.item_permission, permissions, false);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        ((TextView) r.findViewById(R.id.desc)).setText(desc);
        ImageView icon = r.findViewById(R.id.icon);
        if (ok == null) {
            icon.setImageResource(R.drawable.ic_info);
            icon.setColorFilter(getColor(R.color.text_secondary));
        } else if (ok) {
            icon.setImageResource(R.drawable.ic_check);
            icon.setColorFilter(getColor(R.color.ok));
        } else {
            icon.setImageResource(R.drawable.ic_warning);
            icon.setColorFilter(getColor(R.color.warn));
        }
        icon.setContentDescription(getString(ok == null ? R.string.perm_info : ok ? R.string.perm_ok : R.string.perm_missing));
        r.setOnClickListener(v -> action.run());
        permissions.addView(r);
    }

    // ---------------------------------------------------------------------------------------
    // Settings controls
    // ---------------------------------------------------------------------------------------

    interface Getter {
        String get();
    }

    interface Setter {
        void set(String v);
    }

    interface BoolGetter {
        boolean get();
    }

    interface BoolSetter {
        void set(boolean v);
    }

    private void segment(int containerId, String[] values, int[] labels, Getter get, Setter set) {
        LinearLayout box = findViewById(containerId);
        box.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        TextView[] views = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            TextView t = (TextView) inf.inflate(R.layout.item_segment, box, false);
            t.setText(labels[i]);
            final String value = values[i];
            t.setOnClickListener(v -> {
                set.set(value);
                for (int k = 0; k < views.length; k++) {
                    views[k].setSelected(values[k].equals(value));
                }
                changed();
            });
            t.setSelected(values[i].equals(get.get()));
            views[i] = t;
            box.addView(t);
        }
    }

    private void bindSwitch(int id, BoolGetter get, BoolSetter set) {
        Switch s = findViewById(id);
        s.setChecked(get.get());
        s.setOnCheckedChangeListener((b, v) -> {
            set.set(v);
            changed();
        });
    }

    /** Saves; the running service picks the change up immediately. */
    private void changed() {
        style.save(this);
        preview.setStyle(style);
        if (MonitorService.isRunning()) {
            showSpeeds(MonitorService.SAMPLER);
        } else {
            showSpeeds(local);
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyInsets(View root) {
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
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
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
