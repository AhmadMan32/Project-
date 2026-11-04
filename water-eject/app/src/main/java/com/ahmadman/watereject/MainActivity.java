package com.ahmadman.watereject;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements CleanService.Listener {

    private static final int REQ_NOTIFICATIONS = 1;
    private static final long UI_TICK_MS = 200;
    private static final int[] DURATIONS = {30, 60, 90};
    private static final int[] PRESETS = {100, 165, 200, 300};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager am;

    private WaveView wave;
    private View toggle;
    private ImageView toggleIcon;
    private TextView toggleLabel;
    private TextView freqText;
    private TextView phaseText;
    private TextView outputText;
    private TextView warnText;

    private TextView segAuto;
    private TextView segManual;
    private View autoPanel;
    private View manualPanel;
    private TextView[] durationViews;
    private TextView[] presetViews;
    private TextView manualFreqLabel;
    private SeekBar manualSeek;
    private TextView waveSine;
    private TextView waveSquare;
    private TextView targetSpeaker;
    private TextView targetEarpiece;
    private TextView targetHint;
    private boolean hasEarpiece;

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            updateLiveText();
            if (CleanService.isRunning()) {
                handler.postDelayed(this, UI_TICK_MS);
            }
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            updateLiveText();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            updateLiveText();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        applyWindowInsets(findViewById(R.id.root));

        wave = findViewById(R.id.wave);
        toggle = findViewById(R.id.toggle);
        toggleIcon = findViewById(R.id.toggle_icon);
        toggleLabel = findViewById(R.id.toggle_label);
        freqText = findViewById(R.id.freq_text);
        phaseText = findViewById(R.id.phase_text);
        outputText = findViewById(R.id.output_text);
        warnText = findViewById(R.id.warn_text);

        wave.setInnerRadius(getResources().getDimension(R.dimen.toggle_size) / 2f);
        toggle.setOnClickListener(v -> onToggle());

        setupMode();
        setupTarget();
        setupOptions();

        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep default
        }
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version_label, version));
    }

    @Override
    protected void onStart() {
        super.onStart();
        CleanService.addListener(this);
        am.registerAudioDeviceCallback(deviceCallback, handler);
        onCleanStateChanged();
    }

    @Override
    protected void onStop() {
        CleanService.removeListener(this);
        am.unregisterAudioDeviceCallback(deviceCallback);
        handler.removeCallbacks(uiTick);
        super.onStop();
    }

    @Override
    public void onCleanStateChanged() {
        boolean running = CleanService.isRunning();
        toggle.setActivated(running);
        toggleIcon.setImageResource(running ? R.drawable.ic_stop : R.drawable.ic_drop);
        int fg = getColor(running ? R.color.on_primary : R.color.primary);
        toggleIcon.setColorFilter(fg);
        toggleLabel.setTextColor(fg);
        toggleLabel.setText(running ? R.string.btn_stop : R.string.btn_start);
        setControlsEnabled(!running);
        wave.invalidate();
        handler.removeCallbacks(uiTick);
        if (running) {
            handler.post(uiTick);
        } else {
            updateLiveText();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Start / stop
    // ---------------------------------------------------------------------------------------

    private void onToggle() {
        if (CleanService.isRunning()) {
            CleanService.stop(this);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        try {
            CleanService.start(this);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void updateLiveText() {
        ToneEngine e = CleanService.engine();
        boolean headphone = !AudioRouter.findHeadphones(am, true).isEmpty();
        if (e != null) {
            freqText.setText(getString(R.string.freq_value, Math.round(e.getCurrentFreq())));
            long remaining = Math.max(0, e.getTotalMs() - e.getElapsedMs());
            String phase = getString(phaseName(e.getCurrentPhase()));
            phaseText.setTextColor(getColor(R.color.text_secondary));
            phaseText.setText(e.getMode() == ToneEngine.MODE_MANUAL
                    ? getString(R.string.phase_manual_running, Format.time(e.getElapsedMs()))
                    : getString(R.string.phase_with_time, phase, Format.time(remaining)));
            int routed = CleanService.getRoutedType();
            String out = routed >= 0 ? deviceName(routed) : targetName(e.getTarget());
            outputText.setText(getString(CleanService.isForced()
                    ? R.string.output_forced : R.string.output_value, out));
            warnText.setVisibility(View.GONE);
        } else {
            boolean manual = Prefs.mode(this) == ToneEngine.MODE_MANUAL;
            float f = manual ? Prefs.frequency(this) : ToneEngine.BASE_FREQ;
            freqText.setText(getString(R.string.freq_value, Math.round(f)));
            boolean done = !manual && CleanService.lastSessionCompleted();
            if (manual) {
                phaseText.setText(R.string.ready_manual);
            } else {
                phaseText.setText(getString(done ? R.string.done_auto : R.string.ready_auto,
                        Format.time(Prefs.duration(this) * 1000L)));
            }
            phaseText.setTextColor(getColor(done ? R.color.success : R.color.text_secondary));
            outputText.setText(getString(R.string.output_value, targetName(effectiveTarget())));
            if (headphone && effectiveTarget() == ToneEngine.TARGET_SPEAKER) {
                warnText.setText(Prefs.forceSpeaker(this) ? R.string.warn_headphone_forced
                        : R.string.warn_headphone_not_forced);
                warnText.setVisibility(View.VISIBLE);
            } else {
                warnText.setVisibility(View.GONE);
            }
        }
    }

    private int effectiveTarget() {
        return hasEarpiece ? Prefs.target(this) : ToneEngine.TARGET_SPEAKER;
    }

    private static int phaseName(int phase) {
        switch (phase) {
            case ToneEngine.PHASE_PULSE:
                return R.string.phase_pulse;
            case ToneEngine.PHASE_SWEEP:
                return R.string.phase_sweep;
            case ToneEngine.PHASE_MANUAL:
                return R.string.phase_manual;
            default:
                return R.string.phase_steady;
        }
    }

    private String targetName(int target) {
        return getString(target == ToneEngine.TARGET_EARPIECE ? R.string.out_earpiece : R.string.out_speaker);
    }

    private String deviceName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return getString(R.string.out_speaker);
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return getString(R.string.out_earpiece);
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return getString(R.string.out_wired);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return getString(R.string.out_usb);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return getString(R.string.out_bt);
            default:
                return getString(R.string.out_other);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Settings UI
    // ---------------------------------------------------------------------------------------

    private void setupMode() {
        segAuto = findViewById(R.id.seg_auto);
        segManual = findViewById(R.id.seg_manual);
        autoPanel = findViewById(R.id.auto_panel);
        manualPanel = findViewById(R.id.manual_panel);
        segAuto.setOnClickListener(v -> setMode(ToneEngine.MODE_AUTO));
        segManual.setOnClickListener(v -> setMode(ToneEngine.MODE_MANUAL));

        durationViews = new TextView[]{findViewById(R.id.dur_30), findViewById(R.id.dur_60),
                findViewById(R.id.dur_90)};
        for (int i = 0; i < durationViews.length; i++) {
            final int seconds = DURATIONS[i];
            durationViews[i].setOnClickListener(v -> {
                Prefs.setDuration(this, seconds);
                refreshSelections();
                updateLiveText();
            });
        }

        manualFreqLabel = findViewById(R.id.manual_freq_label);
        manualSeek = findViewById(R.id.manual_freq);
        manualSeek.setMax((int) (ToneEngine.MAX_FREQ - ToneEngine.MIN_FREQ));
        manualSeek.setProgress(Math.round(Prefs.frequency(this) - ToneEngine.MIN_FREQ));
        manualSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    applyFrequency(ToneEngine.MIN_FREQ + progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });

        presetViews = new TextView[]{findViewById(R.id.preset_1), findViewById(R.id.preset_2),
                findViewById(R.id.preset_3), findViewById(R.id.preset_4)};
        for (int i = 0; i < presetViews.length; i++) {
            final int hz = PRESETS[i];
            presetViews[i].setText(getString(R.string.freq_value, hz));
            presetViews[i].setOnClickListener(v -> {
                manualSeek.setProgress(Math.round(hz - ToneEngine.MIN_FREQ));
                applyFrequency(hz);
            });
        }

        waveSine = findViewById(R.id.wave_sine);
        waveSquare = findViewById(R.id.wave_square);
        waveSine.setOnClickListener(v -> applyWave(ToneEngine.WAVE_SINE));
        waveSquare.setOnClickListener(v -> applyWave(ToneEngine.WAVE_SQUARE));
    }

    private void setMode(int mode) {
        Prefs.setMode(this, mode);
        refreshSelections();
        updateLiveText();
    }

    private void applyFrequency(float hz) {
        Prefs.setFrequency(this, hz);
        CleanService.setManualFrequency(hz);
        refreshSelections();
        if (!CleanService.isRunning()) {
            updateLiveText();
        }
    }

    private void applyWave(int w) {
        Prefs.setWave(this, w);
        CleanService.setManualWave(w);
        refreshSelections();
    }

    private void setupTarget() {
        targetSpeaker = findViewById(R.id.target_speaker);
        targetEarpiece = findViewById(R.id.target_earpiece);
        targetHint = findViewById(R.id.target_hint);
        hasEarpiece = AudioRouter.findOutput(am, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) != null;
        targetSpeaker.setOnClickListener(v -> setTarget(ToneEngine.TARGET_SPEAKER));
        targetEarpiece.setOnClickListener(v -> setTarget(ToneEngine.TARGET_EARPIECE));
        if (!hasEarpiece) {
            targetHint.setText(R.string.target_no_earpiece);
        }
        refreshSelections();
    }

    private void setTarget(int target) {
        Prefs.setTarget(this, target);
        refreshSelections();
        updateLiveText();
    }

    private void setupOptions() {
        bindSwitch(R.id.opt_max, Prefs.maxVolume(this), (b, v) -> Prefs.setMaxVolume(this, v));
        bindSwitch(R.id.opt_vibrate, Prefs.vibrate(this), (b, v) -> Prefs.setVibrate(this, v));
        bindSwitch(R.id.opt_force, Prefs.forceSpeaker(this), (b, v) -> {
            Prefs.setForceSpeaker(this, v);
            updateLiveText();
        });
    }

    private void bindSwitch(int id, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Switch s = findViewById(id);
        s.setChecked(checked);
        s.setOnCheckedChangeListener(l);
    }

    private void refreshSelections() {
        boolean manual = Prefs.mode(this) == ToneEngine.MODE_MANUAL;
        segAuto.setSelected(!manual);
        segManual.setSelected(manual);
        autoPanel.setVisibility(manual ? View.GONE : View.VISIBLE);
        manualPanel.setVisibility(manual ? View.VISIBLE : View.GONE);

        int duration = Prefs.duration(this);
        for (int i = 0; i < durationViews.length; i++) {
            durationViews[i].setSelected(DURATIONS[i] == duration);
        }

        int hz = Math.round(Prefs.frequency(this));
        manualFreqLabel.setText(getString(R.string.manual_freq_label, hz));
        for (int i = 0; i < presetViews.length; i++) {
            presetViews[i].setSelected(PRESETS[i] == hz);
        }
        int w = Prefs.wave(this);
        waveSine.setSelected(w == ToneEngine.WAVE_SINE);
        waveSquare.setSelected(w == ToneEngine.WAVE_SQUARE);

        int target = effectiveTarget();
        targetSpeaker.setSelected(target == ToneEngine.TARGET_SPEAKER);
        targetEarpiece.setSelected(target == ToneEngine.TARGET_EARPIECE);
    }

    /** Settings that only apply at start are locked while cleaning; live ones stay usable. */
    private void setControlsEnabled(boolean enabled) {
        View[] locked = {segAuto, segManual, durationViews[0], durationViews[1], durationViews[2],
                targetSpeaker, findViewById(R.id.opt_max), findViewById(R.id.opt_vibrate),
                findViewById(R.id.opt_force)};
        for (View v : locked) {
            v.setEnabled(enabled);
            v.setAlpha(enabled ? 1f : 0.5f);
        }
        targetEarpiece.setEnabled(enabled && hasEarpiece);
        targetEarpiece.setAlpha(enabled && hasEarpiece ? 1f : 0.5f);
    }

    // ---------------------------------------------------------------------------------------
    // Edge-to-edge (enforced on Android 15)
    // ---------------------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private static void applyWindowInsets(View root) {
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
                Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
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
