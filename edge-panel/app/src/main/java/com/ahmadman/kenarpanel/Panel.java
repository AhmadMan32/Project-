package com.ahmadman.kenarpanel;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ahmadman.kenarpanel.core.Motion;
import com.ahmadman.kenarpanel.core.Playback;
import com.ahmadman.kenarpanel.core.Remotes;

import java.util.ArrayList;
import java.util.List;

/**
 * The panel: app shortcuts, a music card (what is playing, with its buttons) and the TV remote:
 * LG over infrared (power, HDMI 1, HDMI 2) or an Android TV over Wi-Fi (touchpad, back, home,
 * volume, power). Built once and kept in
 * memory. Everything on it is driven by one number, the open progress (0 = hidden at the edge,
 * 1 = open), so it can follow the finger while pulling and animate the same way on its own.
 */
final class Panel {

    interface Host {
        void openApp(String pkg);

        void openSettings(boolean pickApps);

        /** Back key or a tap on the dimmed area. */
        void requestClose();

        /** The edge handle fades out while the panel comes in. */
        void handleAlpha(float alpha);

        /** One-time pairing with an Android TV. */
        void openPair();

        /** The system screen where "notification access" (needed for music control) is switched on. */
        void openMusicAccess();
    }

    static final String[] DEFAULT_APPS = {"com.whatsapp", "com.google.android.youtube", "com.instagram.android",
            "com.android.chrome", "org.telegram.messenger", "com.spotify.music", "com.netflix.mediaclient"};

    private final Context context;
    private final Remote remote;
    private final Host host;
    private final Prefs prefs;
    final PanelRoot root;
    private final LinearLayout content;
    private final View appsCard;
    private final View remoteCard;
    private final Drawable dim;
    private final List<View> cascade = new ArrayList<>();
    private final float dp;
    private boolean right;
    private int travel = 1;
    private float progress;
    private Tween tween;
    private final View pageLg;
    private final View pageAtv;
    private final View tabLg;
    private final View tabAtv;
    private final AtvRemote atv;
    private Tween pageTween;
    private final View foldButton;
    private final View foldArrow;
    private final View remoteClip;
    private final View remoteBody;
    private final View remoteColumn;
    private Tween remoteTween;
    private final Music music;
    private final View musicCard;
    private final View musicInfo;
    private final View musicProgress;
    private final View musicControls;
    private final View musicEmpty;
    private final ImageView musicArt;
    private final ImageView musicPlay;
    private final TextView musicTitle;
    private final TextView musicArtist;
    private final TextView musicPos;
    private final TextView musicLen;
    private final TextView musicEmptyText;
    private final TextView musicAction;
    private final SeekBar musicSeek;
    private boolean shown;
    private boolean seeking;
    /** The screen is tall enough for the music card above the remote (not so in landscape). */
    private boolean musicRoom = true;
    private final Runnable tick = this::tick;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    @SuppressLint("InflateParams") // window root
    Panel(Context c, Remote remote, Host host) {
        this.context = c;
        this.remote = remote;
        this.host = host;
        this.prefs = new Prefs(c);
        dp = c.getResources().getDisplayMetrics().density;
        root = (PanelRoot) LayoutInflater.from(c).inflate(R.layout.panel, null);
        root.setOnClose(host::requestClose);
        dim = root.getBackground().mutate();
        content = root.findViewById(R.id.content);
        appsCard = root.findViewById(R.id.apps_card);
        remoteCard = root.findViewById(R.id.remote_card);
        key(R.id.k_power, Remotes.Key.POWER);
        key(R.id.k_hdmi1, Remotes.Key.HDMI1);
        key(R.id.k_hdmi2, Remotes.Key.HDMI2);
        root.findViewById(R.id.settings).setOnClickListener(v -> host.openSettings(false));
        remoteClip = root.findViewById(R.id.remote_clip);
        remoteBody = root.findViewById(R.id.remote_body);
        remoteColumn = root.findViewById(R.id.remote_column);
        foldButton = root.findViewById(R.id.remote_fold);
        foldArrow = root.findViewById(R.id.remote_fold_arrow);
        foldButton.setOnClickListener(v -> {
            boolean open = !prefs.remoteOpen();
            prefs.remoteOpen(open);
            if (prefs.haptics()) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            showRemote(open, prefs.speed() != Motion.Speed.OFF);
        });

        // Android TV page
        pageLg = root.findViewById(R.id.page_lg);
        pageAtv = root.findViewById(R.id.page_atv);
        tabLg = root.findViewById(R.id.tab_lg);
        tabAtv = root.findViewById(R.id.tab_atv);
        atv = AtvRemote.get(c);
        tabLg.setOnClickListener(v -> select("lg", true));
        tabAtv.setOnClickListener(v -> select("atv", true));
        atvKey(R.id.atv_power, com.ahmadman.kenarpanel.atv.Messages.KEY_POWER, false);
        atvKey(R.id.atv_back, com.ahmadman.kenarpanel.atv.Messages.KEY_BACK, false);
        atvKey(R.id.atv_home, com.ahmadman.kenarpanel.atv.Messages.KEY_HOME, false);
        atvKey(R.id.atv_mute, com.ahmadman.kenarpanel.atv.Messages.KEY_VOLUME_MUTE, false);
        atvKey(R.id.atv_vol_down, com.ahmadman.kenarpanel.atv.Messages.KEY_VOLUME_DOWN, true);
        atvKey(R.id.atv_vol_up, com.ahmadman.kenarpanel.atv.Messages.KEY_VOLUME_UP, true);
        TouchpadView pad = root.findViewById(R.id.atv_pad);
        pad.setKeys(new TouchpadView.Keys() {
            @Override
            public void step(com.ahmadman.kenarpanel.atv.Touchpad.Step s) {
                switch (s) {
                    case UP:
                        atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_UP);
                        break;
                    case DOWN:
                        atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_DOWN);
                        break;
                    case LEFT:
                        atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_LEFT);
                        break;
                    default:
                        atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_RIGHT);
                        break;
                }
            }

            @Override
            public void select() {
                atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_CENTER);
            }
        });
        root.findViewById(R.id.atv_pair).setOnClickListener(v -> host.openPair());
        root.findViewById(R.id.atv_chip).setOnClickListener(v -> {
            AtvRemote.State st = atv.state();
            if (st == AtvRemote.State.NOT_PAIRED || st == AtvRemote.State.REFUSED) {
                host.openPair();
            } else {
                atv.ensure(); // tap to reconnect
            }
        });
        atv.add(this::renderAtv);

        // music card
        music = new Music(c, this::renderMusic);
        musicCard = root.findViewById(R.id.music_card);
        musicInfo = root.findViewById(R.id.music_info);
        musicProgress = root.findViewById(R.id.music_progress);
        musicControls = root.findViewById(R.id.music_controls);
        musicEmpty = root.findViewById(R.id.music_empty);
        musicArt = root.findViewById(R.id.music_art);
        musicArt.setClipToOutline(true); // the cover gets the rounded corners
        musicPlay = root.findViewById(R.id.music_play);
        musicTitle = root.findViewById(R.id.music_title);
        musicArtist = root.findViewById(R.id.music_artist);
        musicPos = root.findViewById(R.id.music_pos);
        musicLen = root.findViewById(R.id.music_len);
        musicEmptyText = root.findViewById(R.id.music_empty_text);
        musicAction = root.findViewById(R.id.music_action);
        musicSeek = root.findViewById(R.id.music_seek);
        musicButton(musicPlay, music::playPause);
        musicButton(root.findViewById(R.id.music_prev), music::previous);
        musicButton(root.findViewById(R.id.music_next), music::next);
        musicInfo.setOnClickListener(v -> {
            Music.Track t = music.track();
            if (t != null && t.pkg != null) {
                host.openApp(t.pkg); // the player itself
            }
        });
        musicAction.setOnClickListener(v -> {
            if (music.state() == Music.State.NO_ACCESS) {
                host.openMusicAccess();
            } else {
                host.openApp(Music.YT_MUSIC);
            }
        });
        musicSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int p, boolean fromUser) {
                if (fromUser) {
                    musicPos.setText(Playback.time(p));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                seeking = false;
                music.seek(b.getProgress());
            }
        });
        refresh();
    }

    /** Re-reads settings (side, apps); called when they change, not on open. */
    void refresh() {
        right = prefs.right();
        content.removeAllViews();
        content.addView(right ? remoteCard : appsCard);
        content.addView(right ? appsCard : remoteCard);
        LinearLayout.LayoutParams gap = (LinearLayout.LayoutParams) remoteCard.getLayoutParams();
        int g = Math.round(10 * dp);
        gap.setMarginStart(right ? 0 : g);
        gap.setMarginEnd(right ? g : 0);
        remoteCard.setLayoutParams(gap);
        // the arrow tab sits on the card's outer side; the card slides toward the app column
        LinearLayout card = (LinearLayout) remoteCard;
        card.removeAllViews();
        card.addView(right ? foldButton : remoteClip);
        card.addView(right ? remoteClip : foldButton);
        LinearLayout.LayoutParams tab = (LinearLayout.LayoutParams) foldButton.getLayoutParams();
        int t = Math.round(4 * dp);
        tab.setMarginStart(right ? 0 : t);
        tab.setMarginEnd(right ? t : 0);
        foldButton.setLayoutParams(tab);
        FrameLayout.LayoutParams column = (FrameLayout.LayoutParams) remoteColumn.getLayoutParams();
        column.gravity = (right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        remoteColumn.setLayoutParams(column);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) content.getLayoutParams();
        lp.gravity = (right ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL;
        content.setLayoutParams(lp);
        loadApps();
        showRemote(prefs.remoteOpen(), false);
        String mode = prefs.tvMode();
        tabLg.setSelected(!"atv".equals(mode));
        tabAtv.setSelected("atv".equals(mode));
        showPage("atv".equals(mode), false);
        ((TouchpadView) root.findViewById(R.id.atv_pad)).setHaptics(prefs.haptics());
        renderAtv();
        followMusic();
        renderMusic();
        boolean ir = remote.available();
        root.findViewById(R.id.ir_missing).setVisibility(ir ? View.GONE : View.VISIBLE);
        for (int id : new int[]{R.id.k_power, R.id.k_hdmi1, R.id.k_hdmi2}) {
            View v = root.findViewById(id);
            v.setEnabled(ir);
            v.setAlpha(ir ? 1f : 0.35f);
        }
        collectCascade();
        fit();
        setProgress(progress);
    }

    /** Things that slide in one after the other: app icons, then the remote's rows. */
    private void collectCascade() {
        cascade.clear();
        LinearLayout apps = root.findViewById(R.id.apps);
        List<View> remoteRows = new ArrayList<>();
        remoteRows.add(musicInfo);
        remoteRows.add(musicProgress);
        remoteRows.add(musicControls);
        remoteRows.add(musicEmpty);
        remoteRows.add(root.findViewById(R.id.remote_header));
        remoteRows.add(root.findViewById(R.id.k_power));
        remoteRows.add(root.findViewById(R.id.power_label));
        remoteRows.add(root.findViewById(R.id.hdmi_row));
        remoteRows.add(root.findViewById(R.id.atv_top));
        remoteRows.add((View) root.findViewById(R.id.atv_pad).getParent());
        remoteRows.add(root.findViewById(R.id.atv_row1));
        remoteRows.add(root.findViewById(R.id.atv_row2));
        remoteRows.add(root.findViewById(R.id.remote_fold));
        List<View> icons = new ArrayList<>();
        for (int i = 0; i < apps.getChildCount(); i++) {
            icons.add(apps.getChildAt(i));
        }
        // in the order they come into view: the remote card leads the slide on either side
        cascade.addAll(remoteRows);
        cascade.addAll(icons);
    }

    /** Sizes the remote to the screen and measures how far the panel travels. */
    void fit() {
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(Math.round(236 * dp), screenW - Math.round((72 + 10 + 30 + 24 + 6) * dp));
        // the column keeps its width while the clip around it narrows (otherwise it would be cut)
        for (View v : new View[]{remoteColumn, remoteBody, musicCard}) { // music as wide as the remote
            ViewGroup.LayoutParams rl = v.getLayoutParams();
            if (rl.width != width) {
                rl.width = width;
                v.setLayoutParams(rl);
            }
        }
        boolean room = screenH >= neededHeight(width);
        if (room != musicRoom) {
            musicRoom = room;
            renderMusic();
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        travel = Math.max(1, content.getMeasuredWidth());
    }

    /** Height of the remote with the music card (showing a song) above it, at their natural sizes. */
    private int neededHeight(int width) {
        int any = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        remoteBody.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), any);
        int inner = View.MeasureSpec.makeMeasureSpec(
                Math.max(0, width - musicCard.getPaddingLeft() - musicCard.getPaddingRight()), View.MeasureSpec.EXACTLY);
        int h = content.getPaddingTop() + content.getPaddingBottom() + remoteBody.getMeasuredHeight()
                + musicCard.getPaddingTop() + musicCard.getPaddingBottom()
                + ((ViewGroup.MarginLayoutParams) musicCard.getLayoutParams()).bottomMargin;
        for (View row : new View[]{musicInfo, musicProgress, musicControls}) {
            row.measure(inner, any); // measured even while hidden: the card must fit when a song starts
            h += row.getMeasuredHeight() + ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin;
        }
        return h;
    }

    /** How far the finger has to pull for the panel to be fully open, in pixels. */
    int travel() {
        return travel;
    }

    float progress() {
        return progress;
    }

    /** Places everything for this open progress (may go slightly above 1 while bouncing). */
    void setProgress(float p) {
        progress = p;
        float c = Motion.clamp(p);
        float off = (1f - p) * travel;
        content.setTranslationX(right ? off : -off);
        dim.setAlpha(Math.round(255 * c));
        host.handleAlpha(1f - Motion.clamp(c * 3f));
        float slide = 18 * dp * (right ? 1 : -1);
        int n = cascade.size();
        for (int i = 0; i < n; i++) {
            float q = Motion.cascade(c, i, n);
            View v = cascade.get(i);
            v.setAlpha(v.isEnabled() || !(v instanceof android.widget.ImageView) ? q : q * 0.35f);
            v.setTranslationX((1f - q) * slide);
        }
    }

    boolean animating() {
        return tween != null && tween.running();
    }

    void cancelAnimation() {
        if (tween != null) {
            tween.cancel();
            tween = null;
        }
    }

    /** Opens with a spring (a small bounce) or closes quickly, from wherever the panel is now. */
    void animateTo(float target, Motion.Speed speed, Runnable end) {
        cancelAnimation();
        if (speed == Motion.Speed.OFF || Math.abs(progress - target) < 0.001f) {
            setProgress(target);
            if (end != null) {
                end.run();
            }
            return;
        }
        boolean opening = target > progress;
        float distance = Math.max(0.35f, Math.abs(target - progress));
        // the spring's motion is mostly in its first half: it feels as fast as the setting
        long millis = Math.round(speed.millis * distance * (opening ? 2.0f : 0.9f));
        tween = Tween.run(progress, target, millis, opening ? Motion::spring : f -> f * f, this::setProgress, end);
    }

    /** The remote card is out (not slid away behind the app column). */
    boolean remoteShown() {
        return remoteClip.getVisibility() == View.VISIBLE;
    }

    /**
     * The arrow tab on the remote's outer side: slides the card sideways under the app column so
     * only the tab stays (the arrow turns around), and slides it back out. The keys keep their
     * size while moving: the card is clipped, not squeezed.
     */
    void showRemote(boolean open, boolean animate) {
        if (remoteTween != null) {
            remoteTween.cancel();
            remoteTween = null;
        }
        float rotTo = open == right ? 180f : 0f; // ‹ = slide away to the left, › = to the right
        foldButton.setContentDescription(context.getString(open ? R.string.remote_hide : R.string.remote_show));
        ViewGroup.LayoutParams lp = remoteClip.getLayoutParams();
        if (!animate) {
            remoteClip.setVisibility(open ? View.VISIBLE : View.GONE);
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            remoteClip.setLayoutParams(lp);
            remoteColumn.setTranslationX(0);
            remoteColumn.setAlpha(1f);
            foldArrow.setRotation(rotTo);
            fit();
            followMusic();
            return;
        }
        int bodyWidth = remoteBody.getLayoutParams().width;
        int full = bodyWidth > 0 ? bodyWidth : Math.round(236 * dp);
        int from = open ? 0 : (remoteClip.getVisibility() == View.VISIBLE && remoteClip.getWidth() > 0
                ? remoteClip.getWidth() : full);
        int to = open ? full : 0;
        float toward = right ? 1 : -1; // toward the app column
        float rotFrom = foldArrow.getRotation();
        remoteClip.setVisibility(View.VISIBLE);
        lp.width = from;
        remoteClip.setLayoutParams(lp);
        remoteColumn.setTranslationX(toward * (full - from));
        if (open && "atv".equals(prefs.tvMode())) {
            atv.ensure();
        }
        if (open) {
            followMusic(); // fresh song info while it slides out
        }
        long millis = Math.round(prefs.speed().millis * (open ? 1.8f : 1.1f));
        remoteTween = Tween.run(0f, 1f, millis, open ? (android.animation.TimeInterpolator) Motion::spring
                : new DecelerateInterpolator(1.4f), f -> {
                    int w = Math.max(0, Math.min(full, Math.round(from + (to - from) * f)));
                    lp.width = w;
                    remoteClip.setLayoutParams(lp);
                    remoteColumn.setTranslationX(toward * (full - w)); // slides with the moving edge
                    remoteColumn.setAlpha(Motion.clamp(w / (float) full * 1.3f));
                    foldArrow.setRotation(rotFrom + (rotTo - rotFrom) * Motion.clamp(f));
                }, () -> {
                    lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    remoteClip.setLayoutParams(lp);
                    remoteColumn.setTranslationX(0);
                    remoteColumn.setAlpha(1f);
                    remoteClip.setVisibility(open ? View.VISIBLE : View.GONE);
                    foldArrow.setRotation(rotTo);
                    fit();
                    followMusic();
                });
    }

    // ---------------------------------------------------------------------------------------
    // Music

    /** The panel window came on screen (true) or went away (false). */
    void shown(boolean on) {
        shown = on;
        followMusic();
    }

    Music music() {
        return music;
    }

    /** Follows the player only while the card can be seen. */
    private void followMusic() {
        if (music == null) {
            return; // still being built
        }
        if (shown && prefs.music() && musicRoom && prefs.remoteOpen()) {
            music.start();
            renderMusic();
        } else {
            music.stop();
            main.removeCallbacks(tick);
        }
    }

    /** Song, artist, cover, play/pause and the progress bar; or a one-line hint. */
    void renderMusic() {
        main.removeCallbacks(tick);
        boolean on = prefs.music() && musicRoom;
        musicCard.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            return;
        }
        Music.State st = music.state();
        Music.Track t = st == Music.State.ACTIVE ? music.track() : null;
        boolean active = t != null;
        musicInfo.setVisibility(active ? View.VISIBLE : View.GONE);
        musicControls.setVisibility(active ? View.VISIBLE : View.GONE);
        musicEmpty.setVisibility(active ? View.GONE : View.VISIBLE);
        if (!active) {
            musicProgress.setVisibility(View.GONE);
            boolean noAccess = st == Music.State.NO_ACCESS;
            musicEmptyText.setText(noAccess ? R.string.music_no_access : R.string.music_idle);
            musicAction.setText(noAccess ? R.string.music_allow : R.string.music_open_yt);
            boolean canOpen = noAccess || launch(context, Music.YT_MUSIC) != null;
            musicAction.setVisibility(canOpen ? View.VISIBLE : View.GONE);
            return;
        }
        CharSequence label = appLabel(t.pkg);
        musicTitle.setText(t.title != null ? t.title : label);
        musicArtist.setText(t.artist != null ? t.artist : label);
        if (t.art != null) {
            musicArt.setImageBitmap(t.art);
            musicArt.setImageTintList(null);
            musicArt.setPadding(0, 0, 0, 0);
        } else {
            musicArt.setImageResource(R.drawable.ic_music);
            musicArt.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.panel_text_dim)));
            int pad = Math.round(10 * dp);
            musicArt.setPadding(pad, pad, pad, pad);
        }
        musicPlay.setImageResource(t.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        musicPlay.setContentDescription(context.getString(t.playing ? R.string.music_pause : R.string.music_play));
        boolean timed = t.duration > 0; // a live stream has no length
        musicProgress.setVisibility(timed ? View.VISIBLE : View.GONE);
        musicSeek.setEnabled(t.canSeek);
        musicSeek.setMax((int) Math.min(Integer.MAX_VALUE, t.duration));
        musicLen.setText(Playback.time(t.duration));
        showPosition(t);
        if (t.playing && timed && shown) {
            main.postDelayed(tick, 500);
        }
    }

    /** While playing, the time and the bar move on by themselves. */
    private void tick() {
        Music.Track t = music.track();
        if (t == null) {
            return;
        }
        showPosition(t);
        if (t.playing && shown) {
            main.postDelayed(tick, 500);
        }
    }

    private void showPosition(Music.Track t) {
        if (seeking) {
            return; // the finger is on the bar
        }
        long p = Playback.position(t.position, t.updatedAt, t.speed, t.playing, Music.now(), t.duration);
        musicSeek.setProgress((int) Math.min(Integer.MAX_VALUE, p));
        musicPos.setText(Playback.time(p));
    }

    private CharSequence appLabel(String pkg) {
        if (pkg == null) {
            return "";
        }
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pkg;
        }
    }

    /** Music buttons act at once, squeeze a little and bounce back. */
    @SuppressLint("ClickableViewAccessibility") // click is wired too
    private void musicButton(View v, Runnable action) {
        v.setOnTouchListener((view, e) -> {
            Motion.Speed speed = prefs.speed();
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (speed != Motion.Speed.OFF) {
                    scale(view, 0.88f, 70, new DecelerateInterpolator());
                }
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                scale(view, 1f, speed == Motion.Speed.OFF ? 0 : Math.round(speed.millis * 1.6f),
                        new OvershootInterpolator(3f));
            }
            return false; // the click still happens
        });
        v.setOnClickListener(view -> {
            action.run();
            if (prefs.haptics()) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        });
    }

    /** Switches between the LG and the Android TV page; the card keeps its size. */
    void select(String mode, boolean animate) {
        if (mode.equals(prefs.tvMode()) && animate) {
            if ("atv".equals(mode)) {
                atv.ensure();
            }
            return;
        }
        prefs.tvMode(mode);
        boolean isAtv = "atv".equals(mode);
        tabLg.setSelected(!isAtv);
        tabAtv.setSelected(isAtv);
        showPage(isAtv, animate && prefs.speed() != Motion.Speed.OFF);
        if (isAtv) {
            atv.ensure(); // connect right away, without asking
        }
        if (prefs.haptics()) {
            (isAtv ? tabAtv : tabLg).performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    /** The LG page stays laid out (invisible) under the Android TV page, so the size never changes. */
    private void showPage(boolean isAtv, boolean animate) {
        if (pageTween != null) {
            pageTween.cancel();
        }
        View in = isAtv ? pageAtv : pageLg;
        View out = isAtv ? pageLg : pageAtv;
        float shift = 14 * dp * (isAtv ? 1 : -1);
        in.setVisibility(View.VISIBLE);
        if (!animate) {
            in.setAlpha(1f);
            in.setTranslationX(0);
            out.setAlpha(0f);
            out.setVisibility(isAtv ? View.INVISIBLE : View.GONE);
            return;
        }
        in.setAlpha(0f);
        in.setTranslationX(shift);
        pageTween = Tween.run(0f, 1f, Math.round(prefs.speed().millis * 1.3f), new DecelerateInterpolator(1.6f), f -> {
            in.setAlpha(f);
            in.setTranslationX(shift * (1f - f));
            out.setAlpha(1f - f);
            out.setTranslationX(-shift * f);
        }, () -> {
            out.setVisibility(isAtv ? View.INVISIBLE : View.GONE);
            out.setTranslationX(0);
        });
    }

    /** Connection status in the chip: green with the TV's name when connected. */
    void renderAtv() {
        AtvRemote.State st = atv.state();
        View chip = root.findViewById(R.id.atv_chip);
        android.widget.TextView name = root.findViewById(R.id.atv_name);
        chip.setActivated(st == AtvRemote.State.READY);
        switch (st) {
            case READY:
                name.setText(atv.name());
                break;
            case CONNECTING:
                name.setText(context.getString(R.string.atv_connecting, atv.name()));
                break;
            case REFUSED:
                name.setText(R.string.atv_refused);
                break;
            case NOT_PAIRED:
                name.setText(R.string.atv_not_paired_short);
                break;
            default:
                name.setText(context.getString(R.string.atv_offline, atv.name()));
                break;
        }
        boolean paired = st != AtvRemote.State.NOT_PAIRED;
        root.findViewById(R.id.atv_unpaired).setVisibility(paired && st != AtvRemote.State.REFUSED
                ? View.GONE : View.VISIBLE);
        ((android.widget.TextView) root.findViewById(R.id.atv_unpaired_text)).setText(
                st == AtvRemote.State.REFUSED ? R.string.atv_refused_panel : R.string.atv_not_paired_panel);
        ((android.widget.TextView) root.findViewById(R.id.atv_pair)).setText(
                st == AtvRemote.State.REFUSED ? R.string.atv_pair_again : R.string.atv_pair);
    }

    /** Android TV keys: sent on touch-down; volume repeats while held. */
    @SuppressLint("ClickableViewAccessibility")
    private void atvKey(int id, int code, boolean repeats) {
        View v = root.findViewById(id);
        Runnable[] repeat = new Runnable[1];
        repeat[0] = () -> {
            atv.key(code);
            main.postDelayed(repeat[0], 130);
        };
        v.setOnTouchListener((view, e) -> {
            Motion.Speed speed = prefs.speed();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    view.setPressed(true);
                    atv.key(code);
                    if (repeats) {
                        main.postDelayed(repeat[0], 400);
                    }
                    if (prefs.haptics()) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    if (speed != Motion.Speed.OFF) {
                        scale(view, 0.9f, 70, new DecelerateInterpolator());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    main.removeCallbacks(repeat[0]);
                    scale(view, 1f, speed == Motion.Speed.OFF ? 0 : Math.round(speed.millis * 1.6f),
                            new OvershootInterpolator(3f));
                    return true;
                default:
                    return true;
            }
        });
        v.setOnClickListener(view -> atv.key(code));
    }

    static List<String> appsOrDefault(Context c, Prefs prefs) {
        if (prefs.appsChosen()) {
            return prefs.apps();
        }
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String p : DEFAULT_APPS) {
            if (pm.getLaunchIntentForPackage(p) != null) {
                out.add(p);
            }
        }
        return out;
    }

    private void loadApps() {
        LinearLayout box = root.findViewById(R.id.apps);
        box.removeAllViews();
        PackageManager pm = context.getPackageManager();
        LayoutInflater inf = LayoutInflater.from(context);
        for (String pkg : appsOrDefault(context, prefs)) {
            CharSequence label;
            try {
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                continue; // uninstalled
            }
            ImageView v = (ImageView) inf.inflate(R.layout.item_app, box, false);
            v.setImageDrawable(icon(pm, pkg));
            v.setContentDescription(label);
            v.setOnClickListener(x -> host.openApp(pkg));
            box.addView(v);
        }
        ImageView add = (ImageView) inf.inflate(R.layout.item_app, box, false);
        add.setImageResource(R.drawable.ic_add);
        add.setBackgroundResource(R.drawable.bg_key);
        int pad = Math.round(14 * dp);
        add.setPadding(pad, pad, pad, pad);
        add.setContentDescription(context.getString(R.string.apps_add));
        add.setOnClickListener(x -> host.openSettings(true));
        box.addView(add);
    }

    /** Keys act on touch-down (no waiting); they squeeze, bounce back and ripple from the finger. */
    @SuppressLint("ClickableViewAccessibility") // click is wired too, for accessibility
    private void key(int id, Remotes.Key key) {
        View v = root.findViewById(id);
        v.setOnTouchListener((view, e) -> {
            Motion.Speed speed = prefs.speed();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.drawableHotspotChanged(e.getX(), e.getY()); // ripple starts under the finger
                    view.setPressed(true);
                    remote.press(key);
                    if (prefs.haptics()) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    if (speed != Motion.Speed.OFF) {
                        scale(view, 0.9f, 70, new DecelerateInterpolator());
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    if (speed == Motion.Speed.OFF) {
                        scale(view, 1f, 0, null);
                    } else {
                        scale(view, 1f, Math.round(speed.millis * 1.6f), new OvershootInterpolator(3f));
                    }
                    return true;
                default:
                    return true;
            }
        });
        v.setOnClickListener(view -> remote.press(key));
    }

    /** Squeeze / bounce of a key; starts on the next frame, no wait for a layout pass. */
    private static void scale(View v, float to, long millis, android.animation.TimeInterpolator interpolator) {
        Object old = v.getTag(R.id.k_hdmi1);
        if (old instanceof Tween) {
            ((Tween) old).cancel();
        }
        v.setTag(R.id.k_hdmi1, Tween.run(v.getScaleX(), to, millis, interpolator, f -> {
            v.setScaleX(f);
            v.setScaleY(f);
        }, null));
    }

    static Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return pm.getDefaultActivityIcon();
        }
    }

    static Intent launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return i;
    }
}
