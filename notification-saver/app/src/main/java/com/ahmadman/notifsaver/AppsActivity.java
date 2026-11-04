package com.ahmadman.notifsaver;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import com.ahmadman.notifsaver.core.Rules;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Choose which apps are recorded: everything except the switched-off ones, or only the chosen ones. */
public class AppsActivity extends BaseActivity {

    static final class App {
        String pkg;
        String name;
        String folded;
        int saved;
        boolean system;
    }

    private Prefs prefs;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private Adapter adapter;
    private String query = "";
    private boolean showSystem;
    private TextView[] modes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        prefs = Prefs.get(this);
        Ui.topBar(this, getString(R.string.choose_apps), null, true);

        LinearLayout box = findViewById(R.id.mode);
        int[] labels = {R.string.mode_all, R.string.mode_selected};
        modes = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView t = (TextView) LayoutInflater.from(this).inflate(R.layout.item_segment, box, false);
            t.setText(labels[i]);
            final boolean selectedMode = i == 1;
            t.setOnClickListener(v -> {
                prefs.onlySelected = selectedMode;
                prefs.save();
                renderMode();
            });
            modes[i] = t;
            box.addView(t);
        }
        renderMode();

        ((EditText) findViewById(R.id.query)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = Rules.fold(s.toString().trim());
                filter();
            }
        });
        Switch sys = findViewById(R.id.show_system);
        sys.setOnCheckedChangeListener((b, on) -> {
            showSystem = on;
            filter();
        });

        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            App a = shown.get(position);
            prefs.setOn(a.pkg, !prefs.isOn(a.pkg));
            adapter.notifyDataSetChanged();
        });
        load();
    }

    private void renderMode() {
        for (int i = 0; i < modes.length; i++) {
            modes[i].setSelected((i == 1) == prefs.onlySelected);
        }
        ((TextView) findViewById(R.id.mode_hint)).setText(prefs.onlySelected ? R.string.mode_selected_hint
                : R.string.mode_all_hint);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void load() {
        Store store = Store.get(this);
        store.reader.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, App> map = new HashMap<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String p = ri.activityInfo.packageName;
                if (!map.containsKey(p)) {
                    App a = new App();
                    a.pkg = p;
                    a.name = ri.loadLabel(pm).toString();
                    map.put(p, a);
                }
            }
            for (Map.Entry<String, String[]> e : store.db.packages().entrySet()) {
                App a = map.get(e.getKey());
                if (a == null) {
                    a = new App();
                    a.pkg = e.getKey();
                    a.name = store.label(e.getKey());
                    map.put(a.pkg, a);
                }
                a.saved = Integer.parseInt(e.getValue()[1]);
            }
            // everything else that is installed, hidden behind "show system apps"
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (!map.containsKey(ai.packageName)) {
                    App a = new App();
                    a.pkg = ai.packageName;
                    a.name = pm.getApplicationLabel(ai).toString();
                    a.system = true;
                    map.put(a.pkg, a);
                }
            }
            map.remove(getPackageName());
            List<App> list = new ArrayList<>(map.values());
            Collator col = Collator.getInstance(Locale.getDefault());
            for (App a : list) {
                a.folded = Rules.fold(a.name + " " + a.pkg);
            }
            Collections.sort(list, (x, y) -> col.compare(x.name, y.name));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all.clear();
                all.addAll(list);
                findViewById(R.id.progress).setVisibility(View.GONE);
                filter();
            });
        });
    }

    private void filter() {
        shown.clear();
        for (App a : all) {
            if ((showSystem || !a.system) && (query.isEmpty() || a.folded.contains(query))) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public App getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return shown.get(position).pkg.hashCode();
        }

        @Override
        public View getView(int position, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(AppsActivity.this).inflate(R.layout.item_app_switch, parent, false);
            }
            App a = shown.get(position);
            Icons.into(v.findViewById(R.id.icon), a.pkg, a.name);
            ((TextView) v.findViewById(R.id.name)).setText(a.name);
            ((TextView) v.findViewById(R.id.info)).setText(a.saved > 0
                    ? getString(R.string.app_saved_count, Ui.number(a.saved), a.pkg) : a.pkg);
            ((Switch) v.findViewById(R.id.on)).setChecked(prefs.isOn(a.pkg));
            return v;
        }
    }
}
