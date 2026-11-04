package com.ahmadman.disableheadphone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements SpeakerService.Listener {

    private static final int REQ_NOTIFICATIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager am;

    private View toggle;
    private ImageView toggleIcon;
    private TextView toggleLabel;
    private TextView stateText;
    private TextView valueHeadphone;
    private TextView valueOutput;
    private TextView valueMethod;
    private SeekBar seekMedia;
    private SeekBar seekCall;
    private TextView batteryButton;

    private AudioTrack testTrack;
    private final Runnable stopTestRunnable = this::stopTest;
    private final Runnable refreshVolumesRunnable = this::refreshVolumes;

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refreshUi();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        applyWindowInsets(findViewById(R.id.root));

        toggle = findViewById(R.id.toggle);
        toggleIcon = findViewById(R.id.toggle_icon);
        toggleLabel = findViewById(R.id.toggle_label);
        stateText = findViewById(R.id.state_text);
        valueHeadphone = findViewById(R.id.value_headphone);
        valueOutput = findViewById(R.id.value_output);
        valueMethod = findViewById(R.id.value_method);
        seekMedia = findViewById(R.id.seek_media);
        seekCall = findViewById(R.id.seek_call);
        batteryButton = findViewById(R.id.btn_battery);

        ((TextView) findViewById(R.id.value_android)).setText(getString(
                R.string.status_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        String version = "1.0";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep default
        }
        ((TextView) findViewById(R.id.version)).setText(getString(R.string.version_label, version));

        toggle.setOnClickListener(v -> onToggleClicked());
        findViewById(R.id.btn_test).setOnClickListener(v -> playTest());
        batteryButton.setOnClickListener(v -> requestBatteryExemption());

        setupVolume(seekMedia, AudioManager.STREAM_MUSIC);
        setupVolume(seekCall, AudioManager.STREAM_VOICE_CALL);
        setupSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SpeakerService.addListener(this);
        am.registerAudioDeviceCallback(deviceCallback, handler);
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVolumes();
        refreshBatteryButton();
        refreshUi();
    }

    @Override
    protected void onStop() {
        SpeakerService.removeListener(this);
        am.unregisterAudioDeviceCallback(deviceCallback);
        stopTest();
        super.onStop();
    }

    @Override
    public void onSpeakerStateChanged() {
        refreshUi();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            handler.removeCallbacks(refreshVolumesRunnable);
            handler.postDelayed(refreshVolumesRunnable, 150);
        }
        return super.onKeyUp(keyCode, event);
    }

    // ---------------------------------------------------------------------------------------
    // Toggle
    // ---------------------------------------------------------------------------------------

    private void onToggleClicked() {
        if (SpeakerService.isRunning()) {
            SpeakerService.stop(this);
        } else {
            requestNotificationPermission();
            try {
                SpeakerService.start(this);
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.start_failed, Toast.LENGTH_LONG).show();
            }
        }
        refreshUi();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    // ---------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------

    private void refreshUi() {
        boolean running = SpeakerService.isRunning();
        boolean forcing = SpeakerService.isForcing();
        boolean ok = SpeakerService.isRoutingOk();

        toggle.setActivated(running);
        toggleIcon.setImageResource(running ? R.drawable.ic_speaker : R.drawable.ic_headset);
        int fg = getColor(running ? R.color.on_primary : R.color.primary);
        toggleIcon.setColorFilter(fg);
        toggleLabel.setTextColor(fg);
        toggleLabel.setText(running ? R.string.btn_stop : R.string.btn_start);

        int stateRes;
        int stateColor;
        if (!running) {
            stateRes = R.string.state_off;
            stateColor = R.color.text_secondary;
        } else if (!forcing) {
            stateRes = R.string.state_waiting;
            stateColor = R.color.text_secondary;
        } else if (ok) {
            stateRes = R.string.state_forcing;
            stateColor = R.color.success;
        } else {
            stateRes = R.string.state_failed;
            stateColor = R.color.warning;
        }
        stateText.setText(stateRes);
        stateText.setTextColor(getColor(stateColor));

        List<AudioDeviceInfo> headphones =
                AudioRouter.findHeadphones(am, true);
        valueHeadphone.setText(DeviceLabels.join(this, headphones));
        valueOutput.setText(currentMediaOutput(headphones, running && forcing && ok));
        valueMethod.setText(DeviceLabels.method(this,
                running && forcing ? SpeakerService.getActiveMethod() : AudioRouter.METHOD_NONE));
    }

    private String currentMediaOutput(List<AudioDeviceInfo> headphones, boolean forced) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                AudioAttributes media = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
                List<AudioDeviceInfo> devices = am.getAudioDevicesForAttributes(media);
                if (!devices.isEmpty()) {
                    return DeviceLabels.forType(this, devices.get(0).getType());
                }
            } catch (RuntimeException ignored) {
                // fall through to the estimate below
            }
        }
        if (forced || headphones.isEmpty()) {
            return getString(R.string.out_speaker);
        }
        return DeviceLabels.forType(this, headphones.get(0).getType());
    }

    // ---------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------

    private void setupSettings() {
        RadioGroup group = findViewById(R.id.method_group);
        RadioButton media = findViewById(R.id.method_media);
        if (!AudioRouter.isForceMediaLikelySupported()) {
            media.setEnabled(false);
            ((TextView) findViewById(R.id.method_media_hint)).setText(R.string.method_media_unsupported);
        }
        int method = Prefs.method(this);
        group.check(method == AudioRouter.METHOD_FORCE_MEDIA ? R.id.method_media
                : method == AudioRouter.METHOD_COMMUNICATION ? R.id.method_comm
                : R.id.method_auto);
        group.setOnCheckedChangeListener((g, id) -> {
            int m = id == R.id.method_media ? AudioRouter.METHOD_FORCE_MEDIA
                    : id == R.id.method_comm ? AudioRouter.METHOD_COMMUNICATION
                    : AudioRouter.METHOD_AUTO;
            Prefs.setMethod(this, m);
            SpeakerService.refresh(this);
        });

        bindSwitch(R.id.opt_always, Prefs.alwaysForce(this), (b, v) -> {
            Prefs.setAlwaysForce(this, v);
            SpeakerService.refresh(this);
        });
        bindSwitch(R.id.opt_bluetooth, Prefs.includeBluetooth(this), (b, v) -> {
            Prefs.setIncludeBluetooth(this, v);
            SpeakerService.refresh(this);
        });
        bindSwitch(R.id.opt_boot, Prefs.startOnBoot(this), (b, v) -> Prefs.setStartOnBoot(this, v));
    }

    private void bindSwitch(int id, boolean checked, CompoundButton.OnCheckedChangeListener l) {
        Switch s = findViewById(id);
        s.setChecked(checked);
        s.setOnCheckedChangeListener(l);
    }

    private void refreshBatteryButton() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        batteryButton.setVisibility(exempt ? View.GONE : View.VISIBLE);
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                // No settings screen on this ROM.
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Volume
    // ---------------------------------------------------------------------------------------

    private void setupVolume(SeekBar seek, int stream) {
        seek.setMax(am.getStreamMaxVolume(stream));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) {
                    try {
                        am.setStreamVolume(stream, progress, 0);
                    } catch (RuntimeException ignored) {
                        // e.g. Do Not Disturb restrictions
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                refreshVolumes();
            }
        });
    }

    private void refreshVolumes() {
        seekMedia.setProgress(am.getStreamVolume(AudioManager.STREAM_MUSIC));
        seekCall.setProgress(am.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
    }

    // ---------------------------------------------------------------------------------------
    // Test sound: a short rising chime played as normal media audio
    // ---------------------------------------------------------------------------------------

    private void playTest() {
        stopTest();
        final int rate = 44100;
        final double[] notes = {523.25, 659.25, 783.99, 1046.50};
        final int noteFrames = (int) (rate * 0.18);
        short[] pcm = new short[noteFrames * notes.length];
        int attack = (int) (rate * 0.01);
        for (int n = 0; n < notes.length; n++) {
            for (int i = 0; i < noteFrames; i++) {
                double env = Math.min(1.0, i / (double) attack)
                        * Math.pow(1.0 - i / (double) noteFrames, 1.5);
                double s = Math.sin(2 * Math.PI * notes[n] * i / rate)
                        + 0.25 * Math.sin(4 * Math.PI * notes[n] * i / rate);
                pcm[n * noteFrames + i] = (short) (s * env * 0.55 * Short.MAX_VALUE);
            }
        }
        try {
            AudioTrack t = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    pcm.length * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            t.write(pcm, 0, pcm.length);
            t.play();
            testTrack = t;
            handler.postDelayed(stopTestRunnable, pcm.length * 1000L / rate + 300);
        } catch (RuntimeException ignored) {
            testTrack = null;
        }
    }

    private void stopTest() {
        handler.removeCallbacks(stopTestRunnable);
        if (testTrack != null) {
            try {
                testTrack.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            testTrack.release();
            testTrack = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Edge-to-edge (enforced on Android 15)
    // ---------------------------------------------------------------------------------------

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
