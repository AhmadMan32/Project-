package com.ahmadman.linkdownloader;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ahmadman.linkdownloader.core.Commands;
import com.ahmadman.linkdownloader.core.ErrorHints;
import com.ahmadman.linkdownloader.core.LinkDetector;
import com.ahmadman.linkdownloader.core.Platform;
import com.ahmadman.linkdownloader.core.Progress;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Paste or share a link, see what it is, download MP4 / MP3. */
public class MainActivity extends Activity implements Downloads.Listener, App.EngineListener {

    private static final int REQ_LOGIN = 1;
    private static final int REQ_STORAGE = 2;
    private static final int REQ_NOTIFICATIONS = 3;
    private static final int[] HEIGHT_IDS = {R.id.q2160, R.id.q1440, R.id.q1080, R.id.q720, R.id.q480, R.id.q360};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService infoExec = Executors.newSingleThreadExecutor();
    private final Runnable analyzeRunnable = this::analyze;
    private SharedPreferences prefs;

    private EditText link;
    private TextView platformChip;
    private TextView linkHint;
    private View previewCard;
    private ImageView thumb;
    private ProgressBar previewLoading;
    private TextView previewTitle;
    private TextView previewMeta;
    private TextView previewNote;
    private TextView mp4Button;
    private TextView mp3Button;
    private TextView bothButton;
    private LinearLayout list;
    private TextView emptyList;
    private TextView engineStatus;
    private TextView instagramStatus;
    private TextView instagramButton;
    private TextView updateButton;

    private String analyzedUrl;
    private int analyzeToken;
    private Engine.Info info;
    private int height;
    private String audioFormat;
    private String pendingKind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.applyInsets(findViewById(R.id.root));
        prefs = Engine.prefs(this);
        height = prefs.getInt("height", Commands.DEFAULT_HEIGHT);
        audioFormat = prefs.getString("audio_format", Commands.MP3);

        link = findViewById(R.id.link);
        platformChip = findViewById(R.id.platform);
        linkHint = findViewById(R.id.link_hint);
        previewCard = findViewById(R.id.preview_card);
        thumb = findViewById(R.id.thumb);
        previewLoading = findViewById(R.id.preview_loading);
        previewTitle = findViewById(R.id.preview_title);
        previewMeta = findViewById(R.id.preview_meta);
        previewNote = findViewById(R.id.preview_note);
        mp4Button = findViewById(R.id.download_mp4);
        mp3Button = findViewById(R.id.download_mp3);
        bothButton = findViewById(R.id.download_both);
        list = findViewById(R.id.downloads);
        emptyList = findViewById(R.id.downloads_empty);
        engineStatus = findViewById(R.id.engine_status);
        instagramStatus = findViewById(R.id.instagram_status);
        instagramButton = findViewById(R.id.instagram_button);
        updateButton = findViewById(R.id.update_button);

        link.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onLinkChanged();
            }
        });
        findViewById(R.id.paste).setOnClickListener(v -> paste());
        findViewById(R.id.clear).setOnClickListener(v -> link.setText(""));
        for (int i = 0; i < HEIGHT_IDS.length; i++) {
            final int h = Commands.HEIGHTS[i];
            findViewById(HEIGHT_IDS[i]).setOnClickListener(v -> {
                height = h;
                prefs.edit().putInt("height", h).apply();
                renderOptions();
            });
        }
        findViewById(R.id.fmt_mp3).setOnClickListener(v -> setAudio(Commands.MP3));
        findViewById(R.id.fmt_m4a).setOnClickListener(v -> setAudio(Commands.M4A));
        mp4Button.setOnClickListener(v -> download(Commands.VIDEO));
        mp3Button.setOnClickListener(v -> download(Commands.AUDIO));
        bothButton.setOnClickListener(v -> download("both"));
        findViewById(R.id.clear_list).setOnClickListener(v -> Downloads.get(this).clearFinished());

        bindSwitch(R.id.opt_h264, "prefer_h264", true);
        bindSwitch(R.id.opt_auto_update, "auto_update", true);
        instagramButton.setOnClickListener(v -> instagram());
        updateButton.setOnClickListener(v -> updateEngine());

        renderOptions();
        onLinkChanged();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** Text shared from Instagram / YouTube / TikTok ("Share" -> Link İndirici). */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) {
                CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                text = cs == null ? null : cs.toString();
            }
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        } else if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            text = cs == null ? null : cs.toString();
        }
        if (text != null) {
            String url = LinkDetector.normalize(text);
            link.setText(url != null ? url : text);
            link.setSelection(link.getText().length());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Downloads.get(this).addListener(this);
        App.addListener(this);
        renderList();
        renderSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // downloads queued before the app was closed continue now
        if (Downloads.get(this).hasQueued() && !DownloadService.isRunning()) {
            DownloadService.start(this);
        }
    }

    @Override
    protected void onStop() {
        Downloads.get(this).removeListener(this);
        App.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        infoExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onDownloadsChanged() {
        renderList();
    }

    @Override
    public void onEngineState() {
        renderSettings();
    }

    // ---------------------------------------------------------------------------------------
    // Link + preview
    // ---------------------------------------------------------------------------------------

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        String url = LinkDetector.normalize(text == null ? null : text.toString());
        if (url == null) {
            Toast.makeText(this, R.string.clipboard_no_link, Toast.LENGTH_SHORT).show();
            return;
        }
        link.setText(url);
        link.setSelection(url.length());
    }

    private String currentUrl() {
        return LinkDetector.normalize(link.getText().toString());
    }

    private void onLinkChanged() {
        String url = currentUrl();
        main.removeCallbacks(analyzeRunnable);
        boolean valid = url != null;
        mp4Button.setEnabled(valid);
        mp3Button.setEnabled(valid);
        bothButton.setEnabled(valid);
        mp4Button.setAlpha(valid ? 1f : 0.45f);
        mp3Button.setAlpha(valid ? 1f : 0.45f);
        bothButton.setAlpha(valid ? 1f : 0.45f);
        if (!valid) {
            platformChip.setVisibility(View.GONE);
            linkHint.setText(link.getText().length() == 0 ? R.string.link_hint : R.string.link_invalid);
            previewCard.setVisibility(View.GONE);
            analyzedUrl = null;
            info = null;
            analyzeToken++;
            return;
        }
        Platform p = LinkDetector.detect(url);
        platformChip.setText(p == Platform.OTHER ? getString(R.string.platform_other) : p.label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.color);
        bg.setCornerRadius(Ui.dp(this, 14));
        platformChip.setBackground(bg);
        platformChip.setVisibility(View.VISIBLE);
        linkHint.setText(p.usesLoginCookies() && !InstagramLoginActivity.isLoggedIn(this)
                ? R.string.link_detected_instagram : R.string.link_detected);
        if (!url.equals(analyzedUrl)) {
            main.postDelayed(analyzeRunnable, 600);
        }
    }

    private void analyze() {
        final String url = currentUrl();
        if (url == null || url.equals(analyzedUrl)) {
            return;
        }
        analyzedUrl = url;
        info = null;
        final int token = ++analyzeToken;
        previewCard.setVisibility(View.VISIBLE);
        previewLoading.setVisibility(View.VISIBLE);
        previewTitle.setText(R.string.preview_loading);
        previewMeta.setText(App.engineReady ? "" : getString(R.string.engine_preparing));
        previewNote.setVisibility(View.GONE);
        thumb.setImageDrawable(null);
        Platform p = LinkDetector.detect(url);
        String cookies = Engine.cookiesFor(this, p);
        infoExec.execute(() -> {
            try {
                Engine.Info i = Engine.info(this, url, cookies);
                main.post(() -> {
                    if (token == analyzeToken && !isFinishing()) {
                        showPreview(i);
                    }
                });
            } catch (Throwable t) {
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                main.post(() -> {
                    if (token == analyzeToken && !isFinishing()) {
                        showPreviewError(p, msg);
                    }
                });
            }
        });
    }

    private void showPreview(Engine.Info i) {
        info = i;
        previewLoading.setVisibility(View.GONE);
        previewTitle.setText(i.title == null || i.title.isEmpty() ? getString(R.string.untitled) : i.title);
        StringBuilder meta = new StringBuilder();
        if (i.uploader != null && !i.uploader.isEmpty()) {
            meta.append(i.uploader);
        }
        String d = Progress.duration(i.duration);
        if (!d.isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append(d);
        }
        if (i.maxHeight > 0) {
            meta.append(meta.length() > 0 ? " · " : "").append(getString(R.string.max_quality, label(i.maxHeight)));
        }
        previewMeta.setText(meta);
        Thumbnails.load(thumb, i.thumbnail, 720);
        if (!i.hasVideo) {
            previewNote.setText(R.string.audio_only);
            previewNote.setVisibility(View.VISIBLE);
        } else if (i.maxHeight > 0 && height > i.maxHeight) {
            previewNote.setText(getString(R.string.lower_quality, label(height), label(i.maxHeight)));
            previewNote.setVisibility(View.VISIBLE);
        } else {
            previewNote.setVisibility(View.GONE);
        }
        renderOptions();
    }

    private void showPreviewError(Platform p, String msg) {
        previewLoading.setVisibility(View.GONE);
        previewTitle.setText(R.string.preview_failed);
        previewMeta.setText(reasonText(ErrorHints.classify(msg).name(), p));
        previewNote.setText(R.string.preview_failed_note);
        previewNote.setVisibility(View.VISIBLE);
        analyzedUrl = null; // allow another try
    }

    static String label(int h) {
        return h >= 2160 ? "4K" : h + "p";
    }

    // ---------------------------------------------------------------------------------------
    // Options & download
    // ---------------------------------------------------------------------------------------

    private void setAudio(String fmt) {
        audioFormat = fmt;
        prefs.edit().putString("audio_format", fmt).apply();
        renderOptions();
    }

    private void renderOptions() {
        int max = info != null && info.maxHeight > 0 ? info.maxHeight : Integer.MAX_VALUE;
        for (int i = 0; i < HEIGHT_IDS.length; i++) {
            View chip = findViewById(HEIGHT_IDS[i]);
            chip.setSelected(Commands.HEIGHTS[i] == height);
            // above what this video offers: still selectable, shown faded
            chip.setAlpha(Commands.HEIGHTS[i] > max && Commands.HEIGHTS[i] != height ? 0.45f : 1f);
        }
        findViewById(R.id.fmt_mp3).setSelected(Commands.MP3.equals(audioFormat));
        findViewById(R.id.fmt_m4a).setSelected(Commands.M4A.equals(audioFormat));
        mp4Button.setText(getString(R.string.download_mp4, label(height)));
        mp3Button.setText(Commands.MP3.equals(audioFormat) ? R.string.download_mp3 : R.string.download_m4a);
        boolean noVideo = info != null && !info.hasVideo;
        mp4Button.setVisibility(noVideo ? View.GONE : View.VISIBLE);
        bothButton.setVisibility(noVideo ? View.GONE : View.VISIBLE);
    }

    private void download(String kind) {
        String url = currentUrl();
        if (url == null) {
            Toast.makeText(this, R.string.link_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        if (MediaSaver.needsStoragePermission()
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingKind = kind;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notifications", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        if (kind.equals("both")) {
            enqueue(url, Commands.VIDEO);
            enqueue(url, Commands.AUDIO);
        } else {
            enqueue(url, kind);
        }
        Toast.makeText(this, R.string.queued, Toast.LENGTH_SHORT).show();
        View card = findViewById(R.id.downloads_card);
        android.widget.ScrollView root = findViewById(R.id.root);
        root.post(() -> root.smoothScrollTo(0, Math.max(0, card.getTop() - Ui.dp(this, 12))));
    }

    private void enqueue(String url, String kind) {
        Item it = new Item();
        it.url = url;
        it.platform = LinkDetector.detect(url).name();
        it.kind = kind;
        it.height = height;
        it.audioFormat = audioFormat;
        if (info != null && url.equals(analyzedUrl)) {
            it.title = info.title;
            it.uploader = info.uploader;
            it.thumbnail = info.thumbnail;
        }
        Downloads.get(this).enqueue(it);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingKind != null) {
                download(pendingKind);
            } else {
                Toast.makeText(this, R.string.storage_needed, Toast.LENGTH_LONG).show();
            }
            pendingKind = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Download list
    // ---------------------------------------------------------------------------------------

    private void renderList() {
        List<Item> items = Downloads.get(this).snapshot();
        list.removeAllViews();
        emptyList.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.clear_list).setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        LayoutInflater inf = LayoutInflater.from(this);
        for (Item it : items) {
            View row = inf.inflate(R.layout.item_download, list, false);
            TextView badge = row.findViewById(R.id.badge);
            boolean video = it.isVideo();
            badge.setText(video ? "MP4" : (Commands.M4A.equals(it.audioFormat) ? "M4A" : "MP3"));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(video ? 0xFF2563EB : 0xFF16A34A);
            bg.setCornerRadius(Ui.dp(this, 8));
            badge.setBackground(bg);
            TextView title = row.findViewById(R.id.title);
            title.setText(it.title != null && !it.title.isEmpty() ? it.title : it.url);
            TextView status = row.findViewById(R.id.status);
            ProgressBar bar = row.findViewById(R.id.bar);
            ImageView action = row.findViewById(R.id.action);
            ImageView share = row.findViewById(R.id.share);
            ImageView remove = row.findViewById(R.id.remove);
            Platform p = platformOf(it);
            String source = p == Platform.OTHER ? "" : p.label + " · ";
            share.setVisibility(View.GONE);
            remove.setVisibility(View.VISIBLE);
            remove.setOnClickListener(v -> Downloads.get(this).remove(it.id));
            status.setTextColor(getColor(R.color.text_secondary));
            switch (it.status) {
                case Item.QUEUED:
                    status.setText(source + getString(R.string.status_queued));
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndeterminate(true);
                    action.setVisibility(View.GONE);
                    remove.setImageResource(R.drawable.ic_close);
                    remove.setContentDescription(getString(R.string.cancel));
                    remove.setOnClickListener(v -> Downloads.get(this).cancel(it.id));
                    break;
                case Item.RUNNING:
                    status.setText(source + (it.detail == null ? "" : it.detail));
                    bar.setVisibility(View.VISIBLE);
                    bar.setIndeterminate(it.percent <= 0);
                    bar.setProgress(Math.round(it.percent));
                    action.setVisibility(View.GONE);
                    remove.setImageResource(R.drawable.ic_close);
                    remove.setContentDescription(getString(R.string.cancel));
                    remove.setOnClickListener(v -> Downloads.get(this).cancel(it.id));
                    break;
                case Item.DONE:
                    StringBuilder sb = new StringBuilder(source);
                    if (it.quality != null) {
                        sb.append(it.quality).append(" · ");
                    }
                    sb.append(Ui.size(it.size)).append(" · ").append(getString(video ? R.string.saved_movies : R.string.saved_music));
                    status.setText(sb);
                    status.setTextColor(getColor(R.color.success));
                    bar.setVisibility(View.GONE);
                    action.setVisibility(View.GONE);
                    share.setVisibility(View.VISIBLE);
                    share.setOnClickListener(v -> share(it));
                    row.setOnClickListener(v -> open(it));
                    remove.setImageResource(R.drawable.ic_close);
                    remove.setContentDescription(getString(R.string.remove_from_list));
                    break;
                default: // failed / cancelled
                    boolean cancelled = Item.CANCELLED.equals(it.status);
                    String why = cancelled ? getString(R.string.status_cancelled) : reasonText(it.reason, p);
                    status.setText(why + (!cancelled && it.error != null && !it.error.isEmpty()
                            && ErrorHints.Reason.UNKNOWN.name().equals(it.reason) ? "\n" + it.error : ""));
                    status.setTextColor(getColor(cancelled ? R.color.text_secondary : R.color.error));
                    bar.setVisibility(View.GONE);
                    action.setVisibility(View.VISIBLE);
                    action.setImageResource(R.drawable.ic_retry);
                    action.setContentDescription(getString(R.string.retry));
                    action.setOnClickListener(v -> Downloads.get(this).retry(it.id));
                    if (!cancelled) {
                        row.setOnClickListener(v -> showError(it, p));
                    }
                    remove.setImageResource(R.drawable.ic_close);
                    remove.setContentDescription(getString(R.string.remove_from_list));
            }
            list.addView(row);
        }
    }

    private static Platform platformOf(Item it) {
        try {
            return Platform.valueOf(it.platform);
        } catch (RuntimeException e) {
            return Platform.OTHER;
        }
    }

    private void showError(Item it, Platform p) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(reasonText(it.reason, p) + (it.error == null ? "" : "\n\n" + it.error))
                .setPositiveButton(R.string.retry, (d, w) -> Downloads.get(this).retry(it.id))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    String reasonText(String reason, Platform p) {
        ErrorHints.Reason r;
        try {
            r = reason == null ? ErrorHints.Reason.UNKNOWN : ErrorHints.Reason.valueOf(reason);
        } catch (RuntimeException e) {
            return getString(R.string.err_interrupted);
        }
        switch (r) {
            case BOT_CHECK:
                return getString(R.string.err_bot);
            case LOGIN_REQUIRED:
                return getString(p == Platform.INSTAGRAM ? R.string.err_login_instagram : R.string.err_login);
            case PRIVATE:
                return getString(R.string.err_private);
            case UNAVAILABLE:
                return getString(R.string.err_unavailable);
            case GEO_BLOCKED:
                return getString(R.string.err_geo);
            case AGE_RESTRICTED:
                return getString(R.string.err_age);
            case UNSUPPORTED:
                return getString(R.string.err_unsupported);
            case RATE_LIMITED:
                return getString(R.string.err_rate);
            case NETWORK:
                return getString(R.string.err_network);
            case NO_FORMATS:
                return getString(R.string.err_no_formats);
            case LIVE:
                return getString(R.string.err_live);
            case STORAGE:
                return getString(R.string.err_storage);
            default:
                return getString(R.string.err_unknown);
        }
    }

    private void open(Item it) {
        if (it.outputUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it.outputUri),
                MediaSaver.mime(it.fileName == null ? "" : it.fileName)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_LONG).show();
        }
    }

    private void share(Item it) {
        if (it.outputUri == null) {
            return;
        }
        Uri u = Uri.parse(it.outputUri);
        Intent i = new Intent(Intent.ACTION_SEND).setType(MediaSaver.mime(it.fileName == null ? "" : it.fileName))
                .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newRawUri("", u));
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    private void bindSwitch(int id, String key, boolean def) {
        Switch s = findViewById(id);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((b, v) -> prefs.edit().putBoolean(key, v).apply());
    }

    private void renderSettings() {
        boolean logged = InstagramLoginActivity.isLoggedIn(this);
        instagramStatus.setText(logged ? R.string.instagram_logged_in : R.string.instagram_logged_out);
        instagramButton.setText(logged ? R.string.logout : R.string.login);
        String status;
        if (App.engineError != null) {
            status = getString(R.string.engine_error, App.engineError);
        } else if (App.updating) {
            status = getString(R.string.engine_updating);
        } else if (!App.engineReady) {
            status = getString(R.string.engine_preparing);
        } else {
            status = getString(R.string.engine_version, App.engineVersion);
        }
        engineStatus.setText(status);
        updateButton.setEnabled(App.engineReady && !App.updating);
        updateButton.setAlpha(App.engineReady && !App.updating ? 1f : 0.5f);
        onLinkChanged();
    }

    private void instagram() {
        if (InstagramLoginActivity.isLoggedIn(this)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.logout_confirm)
                    .setPositiveButton(R.string.logout, (d, w) -> {
                        InstagramLoginActivity.logout(this);
                        renderSettings();
                    })
                    .setNegativeButton(R.string.close, null)
                    .show();
        } else {
            startActivityForResult(new Intent(this, InstagramLoginActivity.class), REQ_LOGIN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_LOGIN) {
            renderSettings();
            if (resultCode == RESULT_OK) {
                analyzedUrl = null; // try the preview again with the session
                onLinkChanged();
            }
        }
    }

    private void updateEngine() {
        if (DownloadService.isRunning()) {
            Toast.makeText(this, R.string.update_wait, Toast.LENGTH_LONG).show();
            return;
        }
        App app = (App) getApplication();
        App.BACKGROUND.execute(() -> {
            String err = app.update();
            main.post(() -> Toast.makeText(this, err == null ? getString(R.string.update_done, App.engineVersion)
                    : getString(R.string.update_failed, err), Toast.LENGTH_LONG).show());
        });
    }
}
