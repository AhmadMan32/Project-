package com.ahmadman.kenarpanel;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Choose (and order) the apps shown in the panel; the order of ticking is the order in the panel. */
public class AppPickerActivity extends Activity {

    static final class App {
        String pkg;
        String name;
        ResolveInfo info;
        Drawable icon; // loaded when first shown
    }

    private Prefs prefs;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final List<String> chosen = new ArrayList<>();
    private BaseAdapter adapter;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        MainActivity.applyInsets(findViewById(R.id.root));
        prefs = new Prefs(this);
        chosen.addAll(Panel.appsOrDefault(this, prefs));
        findViewById(R.id.back).setOnClickListener(v -> finish());

        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, App> map = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName()) || map.containsKey(p)) {
                continue;
            }
            App a = new App();
            a.pkg = p;
            a.name = ri.loadLabel(pm).toString();
            a.info = ri;
            map.put(p, a);
        }
        all.addAll(map.values());
        Collator col = Collator.getInstance(Locale.getDefault());
        Collections.sort(all, (x, y) -> {
            int cx = chosen.indexOf(x.pkg);
            int cy = chosen.indexOf(y.pkg);
            if (cx >= 0 || cy >= 0) {
                return cx < 0 ? 1 : cy < 0 ? -1 : cx - cy; // chosen first, in panel order
            }
            return col.compare(x.name, y.name);
        });

        ListView list = findViewById(R.id.list);
        adapter = new BaseAdapter() {
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
                    v = LayoutInflater.from(AppPickerActivity.this).inflate(R.layout.item_pick, parent, false);
                }
                App a = shown.get(position);
                if (a.icon == null) {
                    a.icon = a.info.loadIcon(getPackageManager());
                }
                ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(a.icon);
                ((TextView) v.findViewById(R.id.name)).setText(a.name);
                int order = chosen.indexOf(a.pkg);
                TextView num = v.findViewById(R.id.order);
                num.setText(order >= 0 ? String.valueOf(order + 1) : "");
                num.setVisibility(order >= 0 ? View.VISIBLE : View.INVISIBLE);
                ((CheckBox) v.findViewById(R.id.check)).setChecked(order >= 0);
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, position, id) -> {
            App a = shown.get(position);
            if (chosen.contains(a.pkg)) {
                chosen.remove(a.pkg);
            } else if (chosen.size() >= Prefs.MAX_APPS) {
                Toast.makeText(this, getString(R.string.apps_max, Prefs.MAX_APPS), Toast.LENGTH_SHORT).show();
                return;
            } else {
                chosen.add(a.pkg);
            }
            prefs.apps(chosen);
            OverlayService.settingsChanged();
            adapter.notifyDataSetChanged();
            renderCount();
        });
        ((EditText) findViewById(R.id.query)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                filter();
            }
        });
        filter();
        renderCount();
    }

    private void filter() {
        shown.clear();
        for (App a : all) {
            if (query.isEmpty() || a.name.toLowerCase(Locale.getDefault()).contains(query)) {
                shown.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void renderCount() {
        ((TextView) findViewById(R.id.count)).setText(getString(R.string.apps_count, chosen.size(), Prefs.MAX_APPS));
    }
}
