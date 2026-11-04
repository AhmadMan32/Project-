package com.ahmadman.pdfconverter.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.convert.Sources;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Home screen: every tool grouped in three sections, with search and "open with" support. */
public class MainActivity extends Activity {

    private LinearLayout sections;
    private TextView incomingBanner;
    private final ArrayList<Uri> incoming = new ArrayList<>();
    private String incomingMime;
    private String query = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.applyInsets(findViewById(R.id.root));
        sections = findViewById(R.id.sections);
        incomingBanner = findViewById(R.id.incoming);
        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(new Locale("tr"));
                build();
            }
        });
        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList("incoming");
            if (saved != null) {
                incoming.addAll(saved);
            }
            incomingMime = savedInstanceState.getString("incomingMime");
        } else {
            readIntent(getIntent());
        }
        build();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        build();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList("incoming", incoming);
        out.putString("incomingMime", incomingMime);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A conversion still running or finished: offer to go back to it.
        JobRunner r = JobRunner.get();
        View resume = findViewById(R.id.resume_job);
        boolean show = r.state() == JobRunner.RUNNING || r.state() == JobRunner.DONE;
        resume.setVisibility(show && Tools.byId(r.toolId()) != null ? View.VISIBLE : View.GONE);
        if (show && Tools.byId(r.toolId()) != null) {
            ((TextView) resume).setText(getString(r.state() == JobRunner.RUNNING ? R.string.resume_running
                    : R.string.resume_done, getString(Tools.byId(r.toolId()).title)));
            resume.setOnClickListener(v -> startActivity(new Intent(this, ToolActivity.class)
                    .putExtra(ToolActivity.EXTRA_TOOL, r.toolId())));
        }
    }

    @SuppressWarnings("deprecation")
    private void readIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) {
                uris.add((Uri) p);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable p : list) {
                    if (p instanceof Uri) {
                        uris.add((Uri) p);
                    }
                }
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        incoming.clear();
        incoming.addAll(uris);
        String mime = intent.getType();
        if (mime == null || mime.endsWith("/*") || mime.equals("application/octet-stream")) {
            mime = Sources.mime(this, uris.get(0));
        }
        incomingMime = mime;
    }

    private void build() {
        sections.removeAllViews();
        if (!incoming.isEmpty()) {
            String name = Sources.displayName(this, incoming.get(0));
            incomingBanner.setText(incoming.size() == 1 ? getString(R.string.incoming_one, name)
                    : getString(R.string.incoming_many, incoming.size()));
            incomingBanner.setVisibility(View.VISIBLE);
            incomingBanner.setOnClickListener(v -> {
                incoming.clear();
                incomingMime = null;
                build();
            });
        } else {
            incomingBanner.setVisibility(View.GONE);
        }
        int[] titles = {R.string.section_from_pdf, R.string.section_to_pdf, R.string.section_tools};
        int shown = 0;
        for (int cat = 0; cat < 3; cat++) {
            List<Tool> tools = new ArrayList<>();
            for (Tool t : Tools.all()) {
                if (t.category == cat && matches(t) && (incomingMime == null || t.accepts(incomingMime))) {
                    tools.add(t);
                }
            }
            if (tools.isEmpty()) {
                continue;
            }
            shown += tools.size();
            TextView header = (TextView) LayoutInflater.from(this).inflate(R.layout.item_section, sections, false);
            header.setText(titles[cat]);
            sections.addView(header);
            LinearLayout row = null;
            for (int i = 0; i < tools.size(); i++) {
                if (i % 2 == 0) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    sections.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                row.addView(card(row, tools.get(i), i % 2 == 0));
            }
            if (tools.size() % 2 == 1) {
                View spacer = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
                lp.setMarginStart(Ui.dp(this, 6));
                row.addView(spacer, lp);
            }
        }
        if (shown == 0) {
            TextView empty = (TextView) LayoutInflater.from(this).inflate(R.layout.item_section, sections, false);
            empty.setText(R.string.no_results);
            sections.addView(empty);
        }
    }

    private boolean matches(Tool t) {
        if (query.isEmpty()) {
            return true;
        }
        Locale tr = new Locale("tr");
        String hay = (getString(t.title) + " " + getString(t.description) + " " + t.badge).toLowerCase(tr);
        for (String part : query.split("\\s+")) {
            if (!hay.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private View card(ViewGroup parent, Tool t, boolean left) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_tool, parent, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = Ui.dp(this, 12);
        if (left) {
            lp.setMarginEnd(Ui.dp(this, 6));
        } else {
            lp.setMarginStart(Ui.dp(this, 6));
        }
        v.setLayoutParams(lp);
        TextView badge = v.findViewById(R.id.badge);
        badge.setText(t.badge);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.color);
        bg.setCornerRadius(Ui.dp(this, 10));
        badge.setBackground(bg);
        ((TextView) v.findViewById(R.id.title)).setText(t.title);
        ((TextView) v.findViewById(R.id.description)).setText(t.description);
        v.setOnClickListener(x -> open(t));
        return v;
    }

    private void open(Tool t) {
        Intent i = new Intent(this, ToolActivity.class).putExtra(ToolActivity.EXTRA_TOOL, t.id);
        if (!incoming.isEmpty()) {
            List<Uri> accepted = new ArrayList<>(t.multiple ? incoming : incoming.subList(0, 1));
            i.putParcelableArrayListExtra(ToolActivity.EXTRA_INPUTS, new ArrayList<>(accepted));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData clip = android.content.ClipData.newRawUri("", accepted.get(0));
            for (int k = 1; k < accepted.size(); k++) {
                clip.addItem(new android.content.ClipData.Item(accepted.get(k)));
            }
            i.setClipData(clip);
        }
        startActivity(i);
    }
}
