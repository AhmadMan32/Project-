package com.ahmadman.disableheadphone;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Forces audio to the phone's built-in speaker even when the system believes
 * headphones are plugged in (e.g. a broken or wet headphone jack).
 *
 * Three techniques are used, chosen per device:
 *
 * 1. {@link #METHOD_FORCE_MEDIA}: hidden {@code AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)}.
 *    Android 6.0 - 8.1 only allow this with the normal MODIFY_AUDIO_SETTINGS permission;
 *    from Android 9 audioserver requires the privileged MODIFY_AUDIO_ROUTING permission.
 *    Best quality: media keeps its normal stereo path, just on the speaker.
 *
 * 2. {@link #METHOD_COMMUNICATION}: switch the audio mode to MODE_IN_COMMUNICATION and route
 *    communication audio to the speaker (setCommunicationDevice on Android 12+,
 *    setSpeakerphoneOn before). While "in call", the audio policy engine routes media through
 *    the phone strategy, so every app's sound follows the speaker. Since Android 12 the mode is
 *    dropped if the requesting app is not playing audio, so a silent keep-alive track runs.
 *    Downside: media goes through the voice-call audio path (call EQ, voice processing, often
 *    mono and narrow band), so music and videos sound like a phone call.
 *
 * 3. {@link #METHOD_SHIZUKU}: the same force use as method 1, but issued from a Shizuku helper
 *    process running as the ADB shell user, which holds MODIFY_AUDIO_ROUTING. Media keeps its
 *    normal quality on any Android version. Needs the Shizuku app to be running.
 */
final class AudioRouter {

    static final int METHOD_NONE = 0;
    static final int METHOD_AUTO = 0;
    static final int METHOD_FORCE_MEDIA = 1;
    static final int METHOD_COMMUNICATION = 2;
    static final int METHOD_SHIZUKU = 3;

    // Hidden constants from android.media.AudioSystem.
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NONE = 0;
    private static final int FORCE_SPEAKER = 1;

    private static final int KEEP_ALIVE_RATE = 16000;

    private static Method sGetForceUse;
    private static Method sSetForceUse;
    private static boolean sReflectionTried;
    private static boolean sAutoMediaFailed;

    private final AudioManager am;
    private int active = METHOD_NONE;
    private AudioTrack keepAlive;

    AudioRouter(Context context) {
        am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    int getActiveMethod() {
        return active;
    }

    /** True when the hidden media force-use call can work on this Android version. */
    static boolean isForceMediaLikelySupported() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.P;
    }

    /**
     * Applies (or re-applies) speaker routing. Idempotent: cheap to call repeatedly.
     *
     * @return true if the routing is confirmed to be in place.
     */
    boolean engage(int preferred) {
        // Shizuku first: best quality on Android 9+. When it is not available (e.g. right after a
        // reboot, before Shizuku is started again) fall back to communication mode so the speaker
        // still works; the watchdog switches back as soon as the helper is connected.
        boolean tryShizuku = preferred == METHOD_SHIZUKU
                || (preferred == METHOD_AUTO && !isForceMediaLikelySupported());
        if (tryShizuku && ShizukuForce.isReady()) {
            if (ShizukuForce.setForceMedia(true)) {
                if (active == METHOD_COMMUNICATION) {
                    releaseCommunication();
                }
                active = METHOD_SHIZUKU;
                return true;
            }
        }
        if (active == METHOD_SHIZUKU) {
            ShizukuForce.setForceMedia(false);
            active = METHOD_NONE;
        }

        boolean tryMedia = preferred == METHOD_FORCE_MEDIA
                || (preferred == METHOD_AUTO && isForceMediaLikelySupported() && !sAutoMediaFailed);
        if (tryMedia) {
            if (setForceMedia(true)) {
                if (active == METHOD_COMMUNICATION) {
                    releaseCommunication();
                }
                active = METHOD_FORCE_MEDIA;
                return true;
            }
            if (preferred == METHOD_FORCE_MEDIA) {
                release();
                return false;
            }
            sAutoMediaFailed = true;
        }
        if (active == METHOD_FORCE_MEDIA) {
            setForceMedia(false);
        }
        active = METHOD_COMMUNICATION;
        return engageCommunication();
    }

    /** Restores normal routing. */
    void release() {
        if (active == METHOD_SHIZUKU) {
            ShizukuForce.setForceMedia(false);
        } else if (active == METHOD_FORCE_MEDIA) {
            setForceMedia(false);
        } else if (active == METHOD_COMMUNICATION) {
            releaseCommunication();
        }
        active = METHOD_NONE;
    }

    // ---------------------------------------------------------------------------------------
    // Method 1: hidden AudioSystem force use
    // ---------------------------------------------------------------------------------------

    private static synchronized boolean loadReflection() {
        if (!sReflectionTried) {
            sReflectionTried = true;
            try {
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                sGetForceUse = audioSystem.getMethod("getForceUse", int.class);
                sSetForceUse = audioSystem.getMethod("setForceUse", int.class, int.class);
            } catch (Throwable t) {
                sGetForceUse = null;
                sSetForceUse = null;
            }
        }
        return sGetForceUse != null && sSetForceUse != null;
    }

    private static int getForceUseMedia() {
        try {
            Object value = sGetForceUse.invoke(null, FOR_MEDIA);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean setForceMedia(boolean speaker) {
        if (!loadReflection()) {
            return false;
        }
        int current = getForceUseMedia();
        if (speaker) {
            if (current == FORCE_SPEAKER) {
                return true;
            }
        } else if (current != FORCE_SPEAKER) {
            // Not ours (or already cleared): leave e.g. FORCE_NO_BT_A2DP untouched.
            return true;
        }
        try {
            Object result = sSetForceUse.invoke(null, FOR_MEDIA, speaker ? FORCE_SPEAKER : FORCE_NONE);
            if (!(result instanceof Integer) || (Integer) result != 0) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        return getForceUseMedia() == (speaker ? FORCE_SPEAKER : FORCE_NONE);
    }

    // ---------------------------------------------------------------------------------------
    // Method 2: communication mode + speaker
    // ---------------------------------------------------------------------------------------

    private static boolean isTelephonyMode(int mode) {
        // MODE_RINGTONE(1), MODE_IN_CALL(2), MODE_CALL_SCREENING(4),
        // MODE_CALL_REDIRECT(5), MODE_COMMUNICATION_REDIRECT(6)
        return mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL
                || mode == 4 || mode == 5 || mode == 6;
    }

    private boolean engageCommunication() {
        // Start playing first: since Android 12 AudioService only keeps MODE_IN_COMMUNICATION
        // for an app that is actively playing or recording.
        startKeepAlive();

        int mode = am.getMode();
        boolean phoneCall = isTelephonyMode(mode);
        if (!phoneCall && mode != AudioManager.MODE_IN_COMMUNICATION) {
            try {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } catch (RuntimeException ignored) {
                // Some ROMs throw instead of ignoring; the verification below reports it.
            }
        }

        boolean routed = routeCommunicationToSpeaker();
        int newMode = am.getMode();
        return routed && (newMode == AudioManager.MODE_IN_COMMUNICATION || isTelephonyMode(newMode));
    }

    private boolean routeCommunicationToSpeaker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioDeviceInfo current = am.getCommunicationDevice();
                if (current != null && current.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    return true;
                }
                for (AudioDeviceInfo device : am.getAvailableCommunicationDevices()) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        return am.setCommunicationDevice(device);
                    }
                }
                return false;
            }
            if (!am.isSpeakerphoneOn()) {
                am.setSpeakerphoneOn(true);
            }
            return am.isSpeakerphoneOn();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void releaseCommunication() {
        stopKeepAlive();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice();
            } else {
                am.setSpeakerphoneOn(false);
            }
            if (am.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                am.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (RuntimeException ignored) {
            // Nothing else we can do.
        }
    }

    private void startKeepAlive() {
        if (keepAlive != null && keepAlive.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            return;
        }
        stopKeepAlive();
        try {
            int frames = KEEP_ALIVE_RATE; // one second of silence, looped forever
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(KEEP_ALIVE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            AudioTrack track = new AudioTrack(attributes, format, frames * 2,
                    AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.write(new short[frames], 0, frames);
            track.setLoopPoints(0, frames, -1);
            track.play();
            keepAlive = track;
        } catch (RuntimeException e) {
            keepAlive = null;
        }
    }

    private void stopKeepAlive() {
        if (keepAlive != null) {
            try {
                keepAlive.stop();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            keepAlive.release();
            keepAlive = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Device detection
    // ---------------------------------------------------------------------------------------

    static boolean isHeadphoneType(int type, boolean includeBluetooth) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return true;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return includeBluetooth;
            default:
                return false;
        }
    }

    static List<AudioDeviceInfo> findHeadphones(AudioManager am, boolean includeBluetooth) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (isHeadphoneType(device.getType(), includeBluetooth)) {
                result.add(device);
            }
        }
        return result;
    }
}
