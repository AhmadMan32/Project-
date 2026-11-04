package com.ahmadman.disableheadphone;

import android.os.Process;

import java.lang.reflect.Method;

/**
 * Shizuku "user service": this class is NOT run inside the app. Shizuku starts it in a separate
 * process with ADB shell rights (uid 2000), or root rights if Shizuku was started with root.
 *
 * Since Android 9 audioserver only accepts {@code AudioSystem.setForceUse} from callers holding
 * MODIFY_AUDIO_ROUTING. Normal apps cannot get it, but the shell user has it, so from here the
 * call works and media audio is forced to the speaker with its normal (non-call) audio path.
 *
 * Keep this class self-contained: it must not touch app resources, Context or other app classes.
 */
public class ForceUseService extends IForceUseService.Stub {

    // Hidden constants from android.media.AudioSystem.
    private static final int FOR_MEDIA = 1;
    private static final int FORCE_NONE = 0;
    private static final int FORCE_SPEAKER = 1;

    static final int ERR_REFLECTION = -1000;
    static final int ERR_CALL = -1001;

    private static Method sGetForceUse;
    private static Method sSetForceUse;

    /** Shizuku needs a public no-argument constructor. */
    public ForceUseService() {
    }

    private static synchronized boolean load() {
        if (sGetForceUse == null || sSetForceUse == null) {
            try {
                Class<?> audioSystem = Class.forName("android.media.AudioSystem");
                sGetForceUse = audioSystem.getMethod("getForceUse", int.class);
                sSetForceUse = audioSystem.getMethod("setForceUse", int.class, int.class);
            } catch (Throwable t) {
                sGetForceUse = null;
                sSetForceUse = null;
                return false;
            }
        }
        return true;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public int setForceMedia(boolean speaker) {
        if (!load()) {
            return ERR_REFLECTION;
        }
        try {
            Object result = sSetForceUse.invoke(null, FOR_MEDIA, speaker ? FORCE_SPEAKER : FORCE_NONE);
            return result instanceof Integer ? (Integer) result : ERR_CALL;
        } catch (Throwable t) {
            return ERR_CALL;
        }
    }

    @Override
    public int getForceMedia() {
        if (!load()) {
            return ERR_REFLECTION;
        }
        try {
            Object value = sGetForceUse.invoke(null, FOR_MEDIA);
            return value instanceof Integer ? (Integer) value : ERR_CALL;
        } catch (Throwable t) {
            return ERR_CALL;
        }
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }
}
