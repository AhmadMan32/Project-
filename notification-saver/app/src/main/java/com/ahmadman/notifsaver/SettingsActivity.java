package com.ahmadman.notifsaver;

import android.app.KeyguardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.notifsaver.core.Formats;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Which apps, always-on mode, lock, retention, permissions, export and delete. */
public class SettingsActivity extends BaseActivity {

    private static final int REQ_EXPORT = 7;
    private static final int[] RETENTION = {0, 7, 30, 90, 365};

    private Prefs prefs;
    private LinearLayout permissions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = Prefs.get(this);
        Ui.topBar(this, getString(R.string.settings_title), null, true);
        permissions = findViewById(R.id.permissions);

        findViewById(R.id.choose_apps).setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));

        Switch keep = findViewById(R.id.opt_keep_alive);
        keep.setChecked(prefs.keepAlive);
        keep.setOnCheckedChangeListener((b, on) -> {
            prefs.keepAlive = on;
            prefs.save();
            if (on && ListenerService.isEnabled(this)) {
                KeepAliveService.start(this);
                Perms.askPostNotificationsOnce(this);
            } else if (!on) {
                KeepAliveService.stop(this);
            }
        });

        Switch ongoing = findViewById(R.id.opt_ongoing);
        ongoing.setChecked(prefs.saveOngoing);
        ongoing.setOnCheckedChangeListener((b, on) -> {
            prefs.saveOngoing = on;
            prefs.save();
        });

        Switch lock = findViewById(R.id.opt_lock);
        lock.setChecked(prefs.lock);
        lock.setOnCheckedChangeListener((b, on) -> {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (on && (km == null || !km.isDeviceSecure())) {
                Toast.makeText(this, R.string.lock_needs_screen_lock, Toast.LENGTH_LONG).show();
                b.setChecked(false);
                return;
            }
            prefs.lock = on;
            prefs.save();
            BaseActivity.unlocked = true; // just proved by being here
            if (on) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            }
        });

        LinearLayout box = findViewById(R.id.seg_retention);
        int[] labels = {R.string.keep_forever, R.string.keep_week, R.string.keep_month, R.string.keep_3months,
                R.string.keep_year};
        TextView[] views = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_segment, box, false);
            t.setText(labels[i]);
            final int days = RETENTION[i];
            t.setOnClickListener(v -> {
                if (days == prefs.retentionDays) {
                    return;
                }
                prefs.retentionDays = days;
                prefs.save();
                for (int k = 0; k < views.length; k++) {
                    views[k].setSelected(RETENTION[k] == days);
                }
                Store.get(this).cleanup();
                loadStats();
            });
            t.setSelected(days == prefs.retentionDays);
            views[i] = t;
            box.addView(t);
        }

        findViewById(R.id.export).setOnClickListener(v -> export());
        findViewById(R.id.delete_all).setOnClickListener(v -> Dialogs.confirm(this, getString(R.string.confirm_delete_all),
                R.string.delete, () -> {
                    Store.get(this).write(() -> Store.get(this).db.deleteAll());
                    Toast.makeText(this, R.string.deleted_all, Toast.LENGTH_SHORT).show();
                    findViewById(R.id.stats).postDelayed(this::loadStats, 300);
                }));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.footer)).setText(getString(R.string.footer, version));
        } catch (Exception ignored) {
            // keep the default text
        }
    }

    @Override
    protected void onUnlockedResume() {
        ((TextView) findViewById(R.id.choose_apps_desc)).setText(prefs.onlySelected
                ? getString(R.string.apps_desc_selected, prefs.selected.size())
                : prefs.excluded.isEmpty() ? getString(R.string.apps_desc_all)
                : getString(R.string.apps_desc_except, prefs.excluded.size()));
        renderPermissions();
        loadStats();
    }

    private void loadStats() {
        Store store = Store.get(this);
        store.reader.execute(() -> {
            int count = store.db.count();
            long bytes = getDatabasePath("notifications.db").length()
                    + new java.io.File(getDatabasePath("notifications.db").getPath() + "-wal").length();
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    ((TextView) findViewById(R.id.stats)).setText(getString(R.string.stats, Ui.number(count),
                            android.text.format.Formatter.formatShortFileSize(this, bytes)));
                }
            });
        });
    }

    private void renderPermissions() {
        permissions.removeAllViews();
        header(R.string.perm_required);
        row(R.string.perm_listener, R.string.perm_listener_desc, ListenerService.isEnabled(this),
                () -> Perms.openListenerSettings(this));
        header(R.string.perm_optional);
        row(R.string.perm_post, R.string.perm_post_desc, Perms.postNotifications(this),
                () -> Perms.openPostNotifications(this));
        row(R.string.perm_battery, R.string.perm_battery_desc, Perms.batteryUnrestricted(this),
                () -> Perms.openBattery(this));
        if (Perms.hibernationApplies()) {
            row(R.string.perm_hibernation, R.string.perm_hibernation_desc, Perms.hibernationOff(this),
                    () -> Perms.openHibernation(this));
        }
        if (Perms.isXiaomi()) {
            row(R.string.perm_xiaomi, R.string.perm_xiaomi_desc, null, () -> Perms.openXiaomi(this));
        }
    }

    private void header(int text) {
        TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_permission_header, permissions, false);
        t.setText(text);
        permissions.addView(t);
    }

    /** @param ok null = cannot be read (shown as a reminder) */
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

    // -------------------------------------------------------------------------------------

    private void export() {
        String day = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(new Date());
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "bildirimler_" + day + ".csv");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeCsv(data.getData());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void writeCsv(Uri uri) {
        Toast.makeText(this, R.string.exporting, Toast.LENGTH_SHORT).show();
        Store store = Store.get(this);
        store.reader.execute(() -> {
            int[] rows = {0};
            boolean ok;
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) {
                    throw new IOException("no stream");
                }
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
                TimeZone tz = TimeZone.getDefault();
                w.write(Formats.csvHeader());
                store.db.forEach(c -> {
                    try {
                        w.write(Formats.csvRow(c, tz));
                        rows[0]++;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                w.flush();
                ok = true;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            final boolean done = ok;
            runOnUiThread(() -> Toast.makeText(this, done ? getString(R.string.exported, Ui.number(rows[0]))
                    : getString(R.string.export_failed), Toast.LENGTH_LONG).show());
        });
    }
}
