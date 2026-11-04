package com.ahmadman.notifsaver;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.ahmadman.notifsaver.core.Formats;

import java.util.List;
import java.util.TimeZone;

/** Overview: recording status, quick links and every app with its latest notification. */
public class MainActivity extends BaseActivity implements Store.Listener {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Rows.GroupAdapter apps;
    private View header;
    private int generation;
    private boolean reconnectTried;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderStatus(); // connection state can change without a new notification
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.topBar(this, getString(R.string.app_name), null, false);
        Ui.action(this, R.id.action1, R.drawable.ic_search, R.string.search_title,
                v -> startActivity(BrowseActivity.of(this, BrowseActivity.SEARCH)));
        Ui.action(this, R.id.action2, R.drawable.ic_settings, R.string.settings_title,
                v -> startActivity(new android.content.Intent(this, SettingsActivity.class)));

        ListView list = findViewById(R.id.list);
        header = LayoutInflater.from(this).inflate(R.layout.home_header, list, false);
        list.addHeaderView(header, null, false);
        apps = new Rows.GroupAdapter(this, true);
        list.setAdapter(apps);
        list.setOnItemClickListener((p, v, position, id) -> {
            Object o = p.getItemAtPosition(position);
            if (o instanceof Db.Group) {
                startActivity(BrowseActivity.app(this, ((Db.Group) o).pkg));
            }
        });
        list.setOnItemLongClickListener((p, v, position, id) -> {
            Object o = p.getItemAtPosition(position);
            if (!(o instanceof Db.Group)) {
                return false;
            }
            Db.Group g = (Db.Group) o;
            Dialogs.confirm(this, getString(R.string.confirm_delete_app, g.app != null ? g.app : g.pkg), R.string.delete,
                    () -> Store.get(this).write(() -> Store.get(this).db.deleteApp(g.pkg)));
            return true;
        });

        header.findViewById(R.id.setup_grant).setOnClickListener(v -> Perms.openListenerSettings(this));
        header.findViewById(R.id.restricted_open).setOnClickListener(v -> Perms.openAppDetails(this));
        header.findViewById(R.id.status_fix).setOnClickListener(v -> {
            reconnectTried = true;
            ListenerService.rebind(this, true);
            handler.postDelayed(this::renderStatus, 1500);
        });
        header.findViewById(R.id.search_box).setOnClickListener(
                v -> startActivity(BrowseActivity.of(this, BrowseActivity.SEARCH)));
        header.findViewById(R.id.open_all).setOnClickListener(
                v -> startActivity(BrowseActivity.of(this, BrowseActivity.ALL)));
        header.findViewById(R.id.open_deleted).setOnClickListener(
                v -> startActivity(BrowseActivity.of(this, BrowseActivity.DELETED)));
    }

    @Override
    protected void onUnlockedResume() {
        Store.get(this).addListener(this);
        if (ListenerService.isEnabled(this)) {
            if (Prefs.get(this).keepAlive) {
                KeepAliveService.start(this);
                Perms.askPostNotificationsOnce(this);
            }
            ListenerService.rebind(this, false);
        }
        renderStatus();
        reload();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 3000);
    }

    @Override
    protected void onPause() {
        Store.get(this).removeListener(this);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onSaved() {
        reload();
    }

    private int today;
    private int total;
    private long lastTime;

    private void reload() {
        final int gen = ++generation;
        final Db db = Store.get(this).db;
        Store.get(this).reader.execute(() -> {
            List<Db.Group> groups = db.apps();
            int all = db.count();
            int day = db.countSince(startOfToday());
            int del = db.deletedCount();
            long latest = db.lastTime();
            runOnUiThread(() -> {
                if (gen != generation || isFinishing() || isDestroyed()) {
                    return;
                }
                total = all;
                today = day;
                lastTime = latest;
                apps.set(groups);
                ((TextView) header.findViewById(R.id.all_count)).setText(Ui.number(all));
                ((TextView) header.findViewById(R.id.deleted_count)).setText(Ui.number(del));
                header.findViewById(R.id.empty).setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
                header.findViewById(R.id.apps_label).setVisibility(groups.isEmpty() ? View.GONE : View.VISIBLE);
                renderStatus();
            });
        });
    }

    private static long startOfToday() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void renderStatus() {
        boolean enabled = ListenerService.isEnabled(this);
        View setup = header.findViewById(R.id.setup);
        View status = header.findViewById(R.id.status);
        setup.setVisibility(enabled ? View.GONE : View.VISIBLE);
        status.setVisibility(enabled ? View.VISIBLE : View.GONE);
        header.findViewById(R.id.restricted).setVisibility(!enabled && Perms.restrictedSettingsApply() ? View.VISIBLE : View.GONE);
        if (!enabled) {
            return;
        }
        boolean live = ListenerService.connected;
        header.findViewById(R.id.status_dot).setBackgroundResource(live ? R.drawable.bg_dot : R.drawable.bg_dot_warn);
        ((TextView) header.findViewById(R.id.status_title)).setText(live ? R.string.status_live : R.string.status_waiting);
        StringBuilder sb = new StringBuilder(getString(R.string.status_counts, Ui.number(today), Ui.number(total)));
        if (lastTime > 0) {
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            String when = Formats.sameDay(lastTime, now, tz) ? Formats.time(lastTime, tz)
                    : Formats.shortWhen(lastTime, now, tz, java.util.Locale.getDefault(), getString(R.string.yesterday))
                    + " " + Formats.time(lastTime, tz);
            sb.append('\n').append(getString(R.string.status_last, when));
        }
        if (!live) {
            sb.append('\n').append(getString(reconnectTried ? R.string.status_waiting_more : R.string.status_waiting_text));
        }
        ((TextView) header.findViewById(R.id.status_text)).setText(sb);
        header.findViewById(R.id.status_fix).setVisibility(live ? View.GONE : View.VISIBLE);
    }
}
