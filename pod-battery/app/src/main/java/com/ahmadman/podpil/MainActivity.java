package com.ahmadman.podpil;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.podpil.core.Crypto;
import com.ahmadman.podpil.core.Estimator;
import com.ahmadman.podpil.core.Status;

import java.util.Date;

/** Battery of each pod and the case, how long it will last, settings and permissions. */
public class MainActivity extends Activity implements PodState.Listener {

    private static final int REQ_PERMS = 3;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private PodsView pods;
    private boolean askedOnce;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));
        prefs = new Prefs(this);
        pods = findViewById(R.id.pods);

        findViewById(R.id.grant).setOnClickListener(v -> askPermissions());
        findViewById(R.id.bt_enable).setOnClickListener(v -> enableBluetooth());
        findViewById(R.id.source_info).setOnClickListener(v -> explainSource());
        findViewById(R.id.keys_enter).setOnClickListener(v -> enterKeys());
        findViewById(R.id.keys_clear).setOnClickListener(v -> {
            prefs.clearKeys();
            PodService.keysChanged();
            render();
        });

        Switch popup = findViewById(R.id.opt_popup);
        popup.setChecked(prefs.popup());
        popup.setOnCheckedChangeListener((b, on) -> {
            prefs.popup(on);
            if (on && !Perms.overlay(this)) {
                Perms.openOverlay(this);
            }
        });
        Switch boot = findViewById(R.id.opt_boot);
        boot.setChecked(prefs.boot());
        boot.setOnCheckedChangeListener((b, on) -> prefs.boot(on));
        findViewById(R.id.test_popup).setOnClickListener(v -> {
            if (!Perms.overlay(this)) {
                Perms.openOverlay(this);
            } else if (PodState.status == null) {
                Toast.makeText(this, R.string.popup_no_data, Toast.LENGTH_SHORT).show();
            } else {
                Popup.show(this);
            }
        });

        LinearLayout seg = findViewById(R.id.seg_alert);
        int[] values = {0, 10, 20, 30};
        TextView[] views = new TextView[values.length];
        for (int i = 0; i < values.length; i++) {
            TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_segment, seg, false);
            t.setText(values[i] == 0 ? getString(R.string.off) : Texts.pct(this, values[i]));
            final int v = values[i];
            t.setOnClickListener(x -> {
                prefs.lowAlert(v);
                for (int k = 0; k < views.length; k++) {
                    views[k].setSelected(values[k] == v);
                }
            });
            t.setSelected(values[i] == prefs.lowAlert());
            views[i] = t;
            seg.addView(t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PodState.add(this);
        if (Perms.bluetooth(this)) {
            PodService.start(this);
        } else if (!askedOnce) {
            askedOnce = true;
            askPermissions();
        }
        PodService.setUiVisible(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
        renderPermissions();
    }

    @Override
    protected void onPause() {
        PodState.remove(this);
        PodService.setUiVisible(false);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onPodsChanged() {
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (Perms.bluetooth(this)) {
            PodService.start(this);
            PodService.setUiVisible(true);
        }
        render();
        renderPermissions();
    }

    private void askPermissions() {
        boolean denied = false;
        for (String p : Perms.runtime()) {
            if (!Perms.granted(this, p) && !shouldShowRequestPermissionRationale(p) && askedBefore()) {
                denied = true;
            }
        }
        if (denied && !Perms.bluetooth(this)) {
            Perms.openAppDetails(this); // "don't ask again": only the settings screen helps
            return;
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("asked", true).apply();
        requestPermissions(Perms.runtime(), REQ_PERMS);
    }

    private boolean askedBefore() {
        return getSharedPreferences("settings", MODE_PRIVATE).getBoolean("asked", false);
    }

    @SuppressLint("MissingPermission")
    private void enableBluetooth() {
        try {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (RuntimeException e) {
            Perms.open(this, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    // ---------------------------------------------------------------------------------------

    private void render() {
        boolean perms = Perms.bluetooth(this);
        boolean btOn = PodState.bluetoothOn;
        Status s = PodState.status;
        long now = System.currentTimeMillis();
        boolean connected = PodState.connectedName != null;
        boolean live = PodState.live(now);

        show(R.id.need_perm, !perms);
        show(R.id.bt_off, perms && !btOn);
        show(R.id.pods, perms && s != null);
        show(R.id.waiting, perms && btOn && s == null);
        pods.setStatus(s);

        String title = connected ? PodState.connectedName : s != null ? s.model().name : getString(R.string.no_device);
        ((TextView) findViewById(R.id.device)).setText(title);
        String model = s != null && connected && !s.model().name.equals(title) ? s.model().name : null;
        text(R.id.model, model);

        TextView chip = findViewById(R.id.chip);
        View dot = findViewById(R.id.chip_dot);
        if (connected && live) {
            chip.setText(R.string.chip_live);
            dot.setBackgroundResource(R.drawable.bg_dot_ok);
        } else if (connected) {
            chip.setText(R.string.chip_connected);
            dot.setBackgroundResource(R.drawable.bg_dot_wait);
        } else if (s != null && live) {
            chip.setText(R.string.chip_nearby);
            dot.setBackgroundResource(R.drawable.bg_dot_wait);
        } else if (s != null && s.time > 0) {
            chip.setText(getString(R.string.chip_last, when(s.time, now)));
            dot.setBackgroundResource(R.drawable.bg_dot_off);
        } else {
            chip.setText(R.string.chip_off);
            dot.setBackgroundResource(R.drawable.bg_dot_off);
        }

        // where the numbers come from
        TextView source = findViewById(R.id.source);
        ImageView sourceIcon = findViewById(R.id.source_icon);
        show(R.id.source_row, s != null);
        if (s != null) {
            int text = s.source == Status.Source.AAP ? R.string.source_aap
                    : s.source == Status.Source.DECRYPTED ? R.string.source_decrypted : R.string.source_steps;
            source.setText(text);
            sourceIcon.setImageResource(s.precise() ? R.drawable.ic_check : R.drawable.ic_info);
            sourceIcon.setColorFilter(getColor(s.precise() ? R.color.battery_ok : R.color.text_secondary));
        }

        renderEstimate(s);
        renderDetails(s, now);
        renderExact();
    }

    private void renderEstimate(Status s) {
        Estimator.Estimate e = PodState.estimate;
        boolean show = s != null && e != null;
        show(R.id.estimate_card, show);
        if (!show) {
            return;
        }
        TextView main = findViewById(R.id.est_main);
        TextView label = findViewById(R.id.est_label);
        TextView sub = findViewById(R.id.est_sub);
        if (e.podMinutes >= 0) {
            label.setText(R.string.est_label_left);
            main.setText(getString(R.string.approx, Texts.duration(this, e.podMinutes)));
            sub.setText(e.learned ? getString(R.string.est_basis_learned)
                    : getString(R.string.est_basis_apple, Texts.duration(this, s.model().podMinutes)));
        } else if (e.podsCharging) {
            label.setText(R.string.est_label_charging);
            main.setText(e.podsFullMinutes > 0 ? getString(R.string.approx, Texts.duration(this, e.podsFullMinutes))
                    : getString(R.string.full));
            sub.setText(R.string.est_basis_charging);
        } else {
            label.setText(R.string.est_label_left);
            main.setText(s.bothInCase ? getString(R.string.in_case) : "—");
            sub.setText(R.string.est_basis_none);
        }
        LinearLayout rows = findViewById(R.id.est_rows);
        rows.removeAllViews();
        if (e.caseMinutes >= 0) {
            row(rows, getString(R.string.row_case_extra), getString(R.string.case_extra_value,
                    getString(R.string.approx, Texts.duration(this, e.caseMinutes)), oneDecimal(e.caseCharges)));
        }
        if (e.totalMinutes >= 0 && e.podMinutes >= 0 && e.caseMinutes > 0) {
            row(rows, getString(R.string.row_total), getString(R.string.approx, Texts.duration(this, e.totalMinutes)));
        }
        if (e.caseFullMinutes > 0) {
            row(rows, getString(R.string.row_case_full), getString(R.string.approx, Texts.duration(this, e.caseFullMinutes)));
        }
        rows.setVisibility(rows.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private static String oneDecimal(double v) {
        return String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    private void renderDetails(Status s, long now) {
        LinearLayout rows = findViewById(R.id.details);
        rows.removeAllViews();
        show(R.id.details_card, s != null);
        if (s == null) {
            return;
        }
        row(rows, getString(R.string.row_model), s.model().known ? s.model().name
                : getString(R.string.model_unknown, Integer.toHexString(s.modelId)));
        if (!s.model().single && s.address != null) {
            String ears = s.bothInCase ? getString(R.string.in_case)
                    : getString(R.string.ears_value, yes(s.leftInEar), yes(s.rightInEar));
            row(rows, getString(R.string.row_ears), ears);
            if (s.lidOpen != null) {
                row(rows, getString(R.string.row_lid), getString(s.lidOpen ? R.string.lid_open : R.string.lid_closed));
            }
        }
        if (s.rssi != 0) {
            row(rows, getString(R.string.row_signal), getString(R.string.signal_value, s.rssi));
        }
        if (s.time > 0) {
            row(rows, getString(R.string.row_updated), when(s.time, now));
        }
    }

    private String yes(boolean b) {
        return getString(b ? R.string.yes : R.string.no);
    }

    private String when(long t, long now) {
        long sec = Math.max(0, (now - t) / 1000);
        if (sec < 60) {
            return getString(R.string.seconds_ago, sec);
        }
        if (sec < 3600) {
            return getString(R.string.minutes_ago, sec / 60);
        }
        return DateFormat.getTimeFormat(this).format(new Date(t));
    }

    private void renderExact() {
        int aap = prefs.aapState();
        boolean keys = prefs.encKey() != null;
        TextView status = findViewById(R.id.exact_status);
        if (PodState.aapLive) {
            status.setText(R.string.exact_aap_live);
        } else if (keys) {
            status.setText(R.string.exact_keys);
        } else if (aap == Prefs.AAP_FAILED) {
            status.setText(R.string.exact_unsupported);
        } else if (aap == Prefs.AAP_OK) {
            status.setText(R.string.exact_aap_ok);
        } else {
            status.setText(R.string.exact_untried);
        }
        show(R.id.keys_clear, keys);
    }

    private void row(LinearLayout parent, String label, String value) {
        View r = LayoutInflater.from(this).inflate(R.layout.item_row, parent, false);
        ((TextView) r.findViewById(R.id.label)).setText(label);
        ((TextView) r.findViewById(R.id.value)).setText(value);
        parent.addView(r);
    }

    private void show(int id, boolean visible) {
        findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void text(int id, String s) {
        TextView t = findViewById(id);
        t.setText(s);
        t.setVisibility(s == null ? View.GONE : View.VISIBLE);
    }

    private void explainSource() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.source_dialog_title)
                .setMessage(R.string.source_dialog_text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void enterKeys() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_keys, null);
        EditText irk = v.findViewById(R.id.irk);
        EditText enc = v.findViewById(R.id.enc);
        new AlertDialog.Builder(this)
                .setTitle(R.string.keys_title)
                .setView(v)
                .setPositiveButton(R.string.save, (d, w) -> {
                    byte[] i = Crypto.hexKey(irk.getText().toString());
                    byte[] e = Crypto.hexKey(enc.getText().toString());
                    if (e == null || (irk.getText().length() > 0 && i == null)) {
                        Toast.makeText(this, R.string.keys_invalid, Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.keys(i, e);
                    PodService.keysChanged();
                    Toast.makeText(this, R.string.keys_saved, Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------------------------------

    private void renderPermissions() {
        LinearLayout box = findViewById(R.id.permissions);
        box.removeAllViews();
        header(box, R.string.perm_required);
        permRow(box, R.string.perm_bluetooth, R.string.perm_bluetooth_desc, Perms.bluetooth(this), this::askPermissions);
        if (Perms.locationNeeded()) {
            permRow(box, R.string.perm_location, R.string.perm_location_desc, Perms.locationOn(this),
                    () -> Perms.openLocation(this));
        }
        header(box, R.string.perm_optional);
        permRow(box, R.string.perm_notifications, R.string.perm_notifications_desc, Perms.notifications(this),
                () -> {
                    if (Build.VERSION.SDK_INT >= 33 && !Perms.granted(this, android.Manifest.permission.POST_NOTIFICATIONS)
                            && !askedBefore()) {
                        askPermissions();
                    } else {
                        Perms.openNotifications(this);
                    }
                });
        permRow(box, R.string.perm_overlay, R.string.perm_overlay_desc, Perms.overlay(this), () -> Perms.openOverlay(this));
        permRow(box, R.string.perm_battery, R.string.perm_battery_desc, Perms.batteryUnrestricted(this),
                () -> Perms.openBattery(this));
    }

    private void header(LinearLayout box, int text) {
        TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_permission_header, box, false);
        t.setText(text);
        box.addView(t);
    }

    private void permRow(LinearLayout box, int title, int desc, boolean ok, Runnable action) {
        View r = LayoutInflater.from(this).inflate(R.layout.item_permission, box, false);
        ((TextView) r.findViewById(R.id.title)).setText(title);
        ((TextView) r.findViewById(R.id.desc)).setText(desc);
        ImageView icon = r.findViewById(R.id.icon);
        icon.setImageResource(ok ? R.drawable.ic_check : R.drawable.ic_warning);
        icon.setColorFilter(getColor(ok ? R.color.battery_ok : R.color.battery_low));
        icon.setContentDescription(getString(ok ? R.string.perm_ok : R.string.perm_missing));
        r.setOnClickListener(v -> action.run());
        box.addView(r);
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
