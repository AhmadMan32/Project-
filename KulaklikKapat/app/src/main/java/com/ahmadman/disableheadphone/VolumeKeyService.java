package com.ahmadman.disableheadphone;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes the volume keys change the MEDIA volume while this app holds communication mode.
 *
 * In communication mode Android thinks a call is going on, so the volume keys change the call
 * volume. This service only steps in when ALL of these are true:
 * - the speaker service is running and is using communication mode (not the wireless debugging
 *   method: there the phone stays in normal mode and the keys already work as usual);
 * - no real call is going on: no phone call (ringing, in call...) and no app recording with the
 *   voice-call microphone source (WhatsApp, Telegram... calls).
 * Then the key changes the media volume (and lifts the call volume cap if needed, because Android
 * limits media to the call volume in this mode). Otherwise the key is left to the system, so
 * during a call it changes the call volume as usual.
 */
public class VolumeKeyService extends AccessibilityService {

    private AudioManager am;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (am == null || !shouldRedirect(am)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        code == KeyEvent.KEYCODE_VOLUME_UP
                                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI);
                AudioRouter.liftCallCapForMedia(am);
            } catch (RuntimeException ignored) {
                // e.g. Do Not Disturb restrictions
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only key events are used.
    }

    @Override
    public void onInterrupt() {
    }

    /** True when the keys should change media volume instead of call volume. */
    static boolean shouldRedirect(AudioManager am) {
        return SpeakerService.isRunning()
                && SpeakerService.getActiveMethod() == AudioRouter.METHOD_COMMUNICATION
                && !isRealCallActive(am);
    }

    /** A phone call, or another app's VoIP call (it records with the voice-call source). */
    static boolean isRealCallActive(AudioManager am) {
        int mode = am.getMode();
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
                || mode == 4 || mode == 5 || mode == 6) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                for (AudioRecordingConfiguration rc : am.getActiveRecordingConfigurations()) {
                    if (rc.getClientAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                        return true;
                    }
                }
            } catch (RuntimeException ignored) {
                // assume no call
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // Enabling
    // ---------------------------------------------------------------------------------------

    private static String id(Context c) {
        return new ComponentName(c, VolumeKeyService.class).flattenToString();
    }

    static boolean isEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) {
            return false;
        }
        for (String s : list.split(":")) {
            if (s.equalsIgnoreCase(id(c))) {
                return true;
            }
        }
        return false;
    }

    /** Possible once wireless debugging granted WRITE_SECURE_SETTINGS (see AdbForce). */
    static boolean canToggleDirectly(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return true if the setting was changed directly. */
    static boolean setEnabled(Context c, boolean enabled) {
        if (!canToggleDirectly(c)) {
            return false;
        }
        try {
            String me = id(c);
            String list = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder out = new StringBuilder();
            if (!TextUtils.isEmpty(list)) {
                for (String s : list.split(":")) {
                    if (!s.isEmpty() && !s.equalsIgnoreCase(me)) {
                        if (out.length() > 0) {
                            out.append(':');
                        }
                        out.append(s);
                    }
                }
            }
            if (enabled) {
                if (out.length() > 0) {
                    out.append(':');
                }
                out.append(me);
            }
            Settings.Secure.putString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, out.toString());
            if (enabled) {
                Settings.Secure.putInt(c.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
