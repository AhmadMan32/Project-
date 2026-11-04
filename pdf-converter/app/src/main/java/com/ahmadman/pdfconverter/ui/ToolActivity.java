package com.ahmadman.pdfconverter.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.convert.Sources;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One tool: pick files, set options, convert, then save / share / open the result. */
public class ToolActivity extends Activity implements JobRunner.Listener {

    public static final String EXTRA_TOOL = "tool";
    public static final String EXTRA_INPUTS = "inputs";

    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE_ONE = 2;
    private static final int REQ_SAVE_TREE = 3;
    private static final int REQ_STORAGE = 4;
    private static final int REQ_NOTIFICATIONS = 5;

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> inputs = new ArrayList<>();
    private final Map<String, String> pageInfo = new HashMap<>();
    private final Map<String, View> optionViews = new HashMap<>();
    private final Map<String, View> optionRows = new HashMap<>();
    private Tool tool;
    private SharedPreferences prefs;

    private LinearLayout fileList;
    private TextView pickButton;
    private View optionsCard;
    private TextView startButton;
    private View progressCard;
    private ProgressBar progressBar;
    private TextView progressText;
    private View resultCard;
    private TextView resultTitle;
    private LinearLayout resultFiles;
    private TextView resultSummary;
    private TextView errorText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tool = Tools.byId(getIntent().getStringExtra(EXTRA_TOOL));
        if (tool == null) {
            finish();
            return;
        }
        setContentView(R.layout.activity_tool);
        Ui.applyInsets(findViewById(R.id.root));
        prefs = getSharedPreferences("options_" + tool.id, MODE_PRIVATE);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        TextView badge = findViewById(R.id.badge);
        badge.setText(tool.badge);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(tool.color);
        bg.setCornerRadius(Ui.dp(this, 10));
        badge.setBackground(bg);
        ((TextView) findViewById(R.id.title)).setText(tool.title);
        ((TextView) findViewById(R.id.description)).setText(tool.description);
        TextView note = findViewById(R.id.note);
        if (tool.note != 0) {
            note.setText(tool.note);
            note.setVisibility(View.VISIBLE);
        }

        fileList = findViewById(R.id.files);
        pickButton = findViewById(R.id.pick);
        pickButton.setOnClickListener(v -> pick());
        optionsCard = findViewById(R.id.options_card);
        startButton = findViewById(R.id.start);
        startButton.setOnClickListener(v -> start(null));
        progressCard = findViewById(R.id.progress_card);
        progressBar = findViewById(R.id.progress);
        progressText = findViewById(R.id.progress_text);
        findViewById(R.id.cancel).setOnClickListener(v -> JobRunner.get().cancel());
        resultCard = findViewById(R.id.result_card);
        resultTitle = findViewById(R.id.result_title);
        resultFiles = findViewById(R.id.result_files);
        resultSummary = findViewById(R.id.result_summary);
        errorText = findViewById(R.id.error);
        findViewById(R.id.save_downloads).setOnClickListener(v -> saveToDownloads());
        findViewById(R.id.save_as).setOnClickListener(v -> saveAs());
        findViewById(R.id.share).setOnClickListener(v -> share());
        findViewById(R.id.open).setOnClickListener(v -> openResult());
        findViewById(R.id.again).setOnClickListener(v -> {
            JobRunner.get().reset(this);
            inputs.clear();
            renderFiles();
            render();
        });

        buildOptions();

        if (savedInstanceState != null) {
            ArrayList<Uri> saved = savedInstanceState.getParcelableArrayList("inputs");
            if (saved != null) {
                inputs.addAll(saved);
            }
        } else {
            ArrayList<Uri> given = getIntent().getParcelableArrayListExtra(EXTRA_INPUTS);
            JobRunner r = JobRunner.get();
            if (given != null) {
                inputs.addAll(given);
            } else if (tool.id.equals(r.toolId()) && r.state() != JobRunner.IDLE) {
                inputs.addAll(r.lastInputs());
            }
        }
        renderFiles();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putParcelableArrayList("inputs", inputs);
    }

    @Override
    protected void onStart() {
        super.onStart();
        JobRunner.get().addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        JobRunner.get().removeListener(this);
        saveOptions();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onJobChanged() {
        render();
    }

    // ---------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        if (tool.mimeTypes.length == 1) {
            i.setType(tool.mimeTypes[0]);
        } else {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, tool.mimeTypes);
        }
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, tool.multiple);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        if (requestCode == REQ_PICK) {
            List<Uri> picked = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int k = 0; k < clip.getItemCount(); k++) {
                    picked.add(clip.getItemAt(k).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (!tool.multiple) {
                inputs.clear();
                if (!picked.isEmpty()) {
                    inputs.add(picked.get(0));
                }
            } else {
                inputs.addAll(picked);
            }
            if (JobRunner.get().state() != JobRunner.RUNNING && tool.id.equals(JobRunner.get().toolId())) {
                JobRunner.get().reset(this);
            }
            renderFiles();
            render();
        } else if (requestCode == REQ_SAVE_ONE && data.getData() != null) {
            List<File> out = JobRunner.get().outputs();
            if (!out.isEmpty()) {
                runSave(() -> {
                    Saver.copyTo(this, out.get(0), data.getData());
                    return 1;
                });
            }
        } else if (requestCode == REQ_SAVE_TREE && data.getData() != null) {
            List<File> out = JobRunner.get().outputs();
            runSave(() -> Saver.toTree(this, data.getData(), out));
        }
    }

    private void renderFiles() {
        fileList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < inputs.size(); i++) {
            final int index = i;
            Uri uri = inputs.get(i);
            View row = inf.inflate(R.layout.item_file, fileList, false);
            ((TextView) row.findViewById(R.id.name)).setText(Sources.displayName(this, uri));
            TextView meta = row.findViewById(R.id.meta);
            String size = Ui.size(Sources.size(this, uri));
            String pages = pageInfo.get(uri.toString());
            meta.setText(pages == null ? size : size + " · " + pages);
            if (pages == null) {
                countPages(uri);
            }
            ImageView up = row.findViewById(R.id.up);
            ImageView down = row.findViewById(R.id.down);
            boolean reorder = tool.multiple && inputs.size() > 1;
            up.setVisibility(reorder ? View.VISIBLE : View.GONE);
            down.setVisibility(reorder ? View.VISIBLE : View.GONE);
            up.setEnabled(i > 0);
            up.setAlpha(i > 0 ? 1f : 0.3f);
            down.setEnabled(i < inputs.size() - 1);
            down.setAlpha(i < inputs.size() - 1 ? 1f : 0.3f);
            up.setOnClickListener(v -> move(index, -1));
            down.setOnClickListener(v -> move(index, 1));
            row.findViewById(R.id.remove).setOnClickListener(v -> {
                inputs.remove(index);
                renderFiles();
                render();
            });
            fileList.addView(row);
        }
        pickButton.setText(inputs.isEmpty() ? (tool.multiple ? R.string.pick_files : R.string.pick_file)
                : (tool.multiple ? R.string.add_files : R.string.change_file));
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= inputs.size()) {
            return;
        }
        Uri u = inputs.remove(index);
        inputs.add(to, u);
        renderFiles();
    }

    /** Shows the page count of PDF inputs (in the background). */
    private void countPages(Uri uri) {
        if (!"application/pdf".equals(Sources.mime(this, uri))) {
            pageInfo.put(uri.toString(), "");
            return;
        }
        pageInfo.put(uri.toString(), "…");
        background.execute(() -> {
            String info;
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) {
                    return;
                }
                try (PdfRenderer r = new PdfRenderer(fd)) {
                    info = getString(R.string.pages_count, r.getPageCount());
                }
            } catch (SecurityException e) {
                info = getString(R.string.encrypted);
            } catch (Exception e) {
                info = "";
            }
            final String text = info;
            main.post(() -> {
                pageInfo.put(uri.toString(), text);
                if (!isFinishing()) {
                    renderFiles();
                }
            });
        });
    }

    // ---------------------------------------------------------------------------------------
    // Options
    // ---------------------------------------------------------------------------------------

    private void buildOptions() {
        LinearLayout container = findViewById(R.id.options);
        LayoutInflater inf = LayoutInflater.from(this);
        if (tool.options.isEmpty()) {
            optionsCard.setVisibility(View.GONE);
            return;
        }
        for (Tool.Option o : tool.options) {
            String saved = o.secret ? o.def : prefs.getString(o.key, o.def);
            View row;
            switch (o.kind) {
                case Tool.Option.CHOICE: {
                    row = inf.inflate(R.layout.option_choice, container, false);
                    ((TextView) row.findViewById(R.id.label)).setText(o.label);
                    RadioGroup group = row.findViewById(R.id.group);
                    for (int i = 0; i < o.values.length; i++) {
                        RadioButton rb = (RadioButton) inf.inflate(R.layout.option_radio, group, false);
                        rb.setId(View.generateViewId());
                        rb.setText(o.labels[i]);
                        rb.setTag(o.values[i]);
                        group.addView(rb);
                        if (o.values[i].equals(saved)) {
                            rb.setChecked(true);
                        }
                    }
                    if (group.getCheckedRadioButtonId() == View.NO_ID && group.getChildCount() > 0) {
                        ((RadioButton) group.getChildAt(0)).setChecked(true);
                    }
                    group.setOnCheckedChangeListener((g, id) -> updateVisibility());
                    optionViews.put(o.key, group);
                    break;
                }
                case Tool.Option.TOGGLE: {
                    row = inf.inflate(R.layout.option_toggle, container, false);
                    Switch sw = row.findViewById(R.id.toggle);
                    sw.setText(o.label);
                    sw.setChecked(Boolean.parseBoolean(saved));
                    optionViews.put(o.key, sw);
                    break;
                }
                default: {
                    row = inf.inflate(R.layout.option_text, container, false);
                    ((TextView) row.findViewById(R.id.label)).setText(o.label);
                    EditText et = row.findViewById(R.id.input);
                    et.setId(View.generateViewId());
                    if (o.hint != 0) {
                        et.setHint(o.hint);
                    }
                    if (o.kind == Tool.Option.PASSWORD) {
                        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    } else if (o.kind == Tool.Option.NUMBER) {
                        et.setInputType(InputType.TYPE_CLASS_NUMBER);
                    } else if (o.kind == Tool.Option.PAGES) {
                        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    }
                    et.setText(o.kind == Tool.Option.PAGES ? "" : saved);
                    optionViews.put(o.key, et);
                }
            }
            optionRows.put(o.key, row);
            container.addView(row);
        }
        updateVisibility();
    }

    private void updateVisibility() {
        Map<String, String> values = optionValues();
        for (Tool.Option o : tool.options) {
            if (o.visibleWhen != null) {
                View row = optionRows.get(o.key);
                boolean show = o.visibleWhen[1].equals(values.get(o.visibleWhen[0]));
                row.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }
    }

    private Map<String, String> optionValues() {
        Map<String, String> out = new HashMap<>();
        for (Tool.Option o : tool.options) {
            View v = optionViews.get(o.key);
            if (v instanceof RadioGroup) {
                RadioGroup g = (RadioGroup) v;
                View checked = g.findViewById(g.getCheckedRadioButtonId());
                out.put(o.key, checked == null ? o.def : String.valueOf(checked.getTag()));
            } else if (v instanceof CompoundButton) {
                out.put(o.key, String.valueOf(((CompoundButton) v).isChecked()));
            } else if (v instanceof EditText) {
                out.put(o.key, ((EditText) v).getText().toString());
            }
        }
        return out;
    }

    private void saveOptions() {
        if (tool == null || optionViews.isEmpty()) {
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, String> entry : optionValues().entrySet()) {
            Tool.Option o = null;
            for (Tool.Option x : tool.options) {
                if (x.key.equals(entry.getKey())) {
                    o = x;
                }
            }
            if (o != null && !o.secret && o.kind != Tool.Option.PAGES) {
                e.putString(entry.getKey(), entry.getValue());
            }
        }
        e.apply();
    }

    // ---------------------------------------------------------------------------------------
    // Run
    // ---------------------------------------------------------------------------------------

    private void start(String password) {
        if (inputs.isEmpty()) {
            Toast.makeText(this, tool.multiple ? R.string.need_files : R.string.need_file, Toast.LENGTH_SHORT).show();
            return;
        }
        if (tool.id.equals("merge") && inputs.size() < 2) {
            Toast.makeText(this, R.string.need_two, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        saveOptions();
        Map<String, String> options = optionValues();
        if (password != null) {
            options.put("__pw_tried", "1"); // a second password prompt then says "wrong password"
        }
        JobRunner.get().start(this, tool, new ArrayList<>(inputs), options, password);
    }

    private void askPassword(String fileName, boolean retry) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_password, null);
        EditText et = v.findViewById(R.id.password);
        TextView msg = v.findViewById(R.id.message);
        msg.setText(getString(retry ? R.string.password_wrong : R.string.password_needed, fileName));
        new AlertDialog.Builder(this)
                .setTitle(R.string.password_title)
                .setView(v)
                .setPositiveButton(R.string.ok, (d, w) -> start(et.getText().toString()))
                .setNegativeButton(R.string.cancel, (d, w) -> JobRunner.get().reset(this))
                .setCancelable(false)
                .show();
    }

    private boolean passwordDialogShown;

    private void render() {
        JobRunner r = JobRunner.get();
        boolean mine = tool.id.equals(r.toolId());
        int state = mine ? r.state() : JobRunner.IDLE;
        boolean otherRunning = !mine && r.state() == JobRunner.RUNNING;

        boolean running = state == JobRunner.RUNNING;
        progressCard.setVisibility(running ? View.VISIBLE : View.GONE);
        resultCard.setVisibility(state == JobRunner.DONE ? View.VISIBLE : View.GONE);
        startButton.setVisibility(running || state == JobRunner.DONE ? View.GONE : View.VISIBLE);
        startButton.setEnabled(!otherRunning);
        startButton.setAlpha(otherRunning ? 0.5f : 1f);
        startButton.setText(otherRunning ? R.string.busy : R.string.start);
        pickButton.setEnabled(!running);
        errorText.setVisibility(View.GONE);

        if (running) {
            if (r.total() > 0) {
                progressBar.setIndeterminate(false);
                progressBar.setMax(r.total());
                progressBar.setProgress(Math.min(r.done(), r.total()));
            } else {
                progressBar.setIndeterminate(true);
            }
            progressText.setText(r.message());
        } else if (state == JobRunner.FAILED) {
            errorText.setText(r.error());
            errorText.setVisibility(View.VISIBLE);
        } else if (state == JobRunner.CANCELLED) {
            errorText.setText(R.string.cancelled);
            errorText.setVisibility(View.VISIBLE);
        } else if (state == JobRunner.NEED_PASSWORD) {
            if (!passwordDialogShown) {
                passwordDialogShown = true;
                boolean retry = r.lastOptions().containsKey("__pw_tried");
                askPassword(r.passwordFile() == null ? "" : r.passwordFile(), retry);
            }
        } else if (state == JobRunner.DONE) {
            renderResult(r);
        }
        if (state != JobRunner.NEED_PASSWORD) {
            passwordDialogShown = false;
        }
    }

    private void renderResult(JobRunner r) {
        List<File> out = r.outputs();
        resultTitle.setText(out.size() == 1 ? getString(R.string.result_one)
                : getString(R.string.result_many, out.size()));
        resultFiles.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        long total = 0;
        int max = Math.min(out.size(), 30);
        for (int i = 0; i < max; i++) {
            File f = out.get(i);
            total += f.length();
            View row = inf.inflate(R.layout.item_result, resultFiles, false);
            ((TextView) row.findViewById(R.id.name)).setText(f.getName());
            ((TextView) row.findViewById(R.id.meta)).setText(Ui.size(f.length()));
            row.setOnClickListener(v -> view(f));
            resultFiles.addView(row);
        }
        for (int i = max; i < out.size(); i++) {
            total += out.get(i).length();
        }
        if (out.size() > max) {
            TextView more = new TextView(this);
            more.setText(getString(R.string.result_more, out.size() - max));
            more.setTextColor(getColor(R.color.text_secondary));
            resultFiles.addView(more);
        }
        String summary = getString(R.string.result_size, Ui.size(total));
        if (tool.id.equals("compress") && r.inputBytes() > 0) {
            long saved = r.inputBytes() - total;
            int pct = (int) Math.round(saved * 100.0 / r.inputBytes());
            summary = pct > 0 ? getString(R.string.result_compressed, Ui.size(r.inputBytes()), Ui.size(total), pct)
                    : getString(R.string.result_not_smaller);
        }
        resultSummary.setText(summary);
        findViewById(R.id.open).setVisibility(out.size() == 1 ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------------------------------
    // Result actions
    // ---------------------------------------------------------------------------------------

    private interface SaveAction {
        int run() throws Exception;
    }

    private void runSave(SaveAction action) {
        background.execute(() -> {
            String msg;
            try {
                int n = action.run();
                msg = getString(R.string.saved, n);
            } catch (Exception e) {
                msg = getString(R.string.save_failed, e.getMessage() == null ? "" : e.getMessage());
            }
            final String m = msg;
            main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    private void saveToDownloads() {
        if (Saver.downloadsNeedPermission()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        List<File> out = JobRunner.get().outputs();
        runSave(() -> Saver.toDownloads(this, out));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveToDownloads();
            } else {
                saveAs();
            }
        }
    }

    private void saveAs() {
        List<File> out = JobRunner.get().outputs();
        if (out.isEmpty()) {
            return;
        }
        try {
            if (out.size() == 1) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(OutputProvider.mimeOf(out.get(0).getName()));
                i.putExtra(Intent.EXTRA_TITLE, out.get(0).getName());
                startActivityForResult(i, REQ_SAVE_ONE);
            } else {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_SAVE_TREE);
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void share() {
        List<File> out = JobRunner.get().outputs();
        if (out.isEmpty()) {
            return;
        }
        Intent i;
        if (out.size() == 1) {
            Uri u = OutputProvider.uriFor(this, out.get(0));
            i = new Intent(Intent.ACTION_SEND).setType(OutputProvider.mimeOf(out.get(0).getName()))
                    .putExtra(Intent.EXTRA_STREAM, u);
            i.setClipData(ClipData.newRawUri("", u));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (File f : out) {
                uris.add(OutputProvider.uriFor(this, f));
                String m = OutputProvider.mimeOf(f.getName());
                type = type == null || type.equals(m) ? m : "*/*";
            }
            i = new Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clip = ClipData.newRawUri("", uris.get(0));
            for (int k = 1; k < uris.size(); k++) {
                clip.addItem(new ClipData.Item(uris.get(k)));
            }
            i.setClipData(clip);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    private void openResult() {
        List<File> out = JobRunner.get().outputs();
        if (!out.isEmpty()) {
            view(out.get(0));
        }
    }

    private void view(File f) {
        Uri u = OutputProvider.uriFor(this, f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, OutputProvider.mimeOf(f.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_LONG).show();
        }
    }

}
