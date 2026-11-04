package com.ahmadman.disableheadphone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

/**
 * App-side manager for the Shizuku helper ({@link ForceUseService}).
 *
 * Shizuku (https://shizuku.rikka.app) is a free app that, once started with Wireless debugging
 * (no PC or root needed), lets other apps run code with ADB shell rights. The shell user may
 * call {@code AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER)}, which forces media audio to the
 * speaker on its normal stereo media path, unlike communication mode which sounds like a call.
 *
 * All methods must be called on the main thread.
 */
final class ShizukuForce {

    static final int STATE_UNSUPPORTED = 0;
    static final int STATE_NOT_INSTALLED = 1;
    static final int STATE_NOT_RUNNING = 2;
    static final int STATE_NO_PERMISSION = 3;
    static final int STATE_CONNECTING = 4;
    static final int STATE_READY = 5;
    static final int STATE_FAILED = 6;

    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    private static final int REQUEST_CODE = 7;
    private static final int FORCE_SPEAKER = 1;
    private static final int FORCE_NONE = 0;
    /** Bump when ForceUseService changes so Shizuku restarts the helper process. */
    private static final int SERVICE_VERSION = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context sApp;
    private static boolean sInitialized;
    private static IForceUseService sService;
    private static boolean sBinding;
    private static boolean sFailed;
    private static int sLastResult;
    private static Shizuku.UserServiceArgs sArgs;

    private ShizukuForce() {
    }

    /** The Shizuku client library needs Android 7.0+. */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    static void addListener(Runnable r) {
        LISTENERS.addIfAbsent(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyChanged() {
        for (Runnable r : LISTENERS) {
            r.run();
        }
    }

    static void init(Context context) {
        if (sInitialized || !isSupported()) {
            return;
        }
        sInitialized = true;
        sApp = context.getApplicationContext();
        sArgs = new Shizuku.UserServiceArgs(
                new ComponentName(sApp.getPackageName(), ForceUseService.class.getName()))
                .daemon(false)
                .processNameSuffix("force")
                .debuggable(false)
                .version(SERVICE_VERSION);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            tryBind();
            notifyChanged();
        }, MAIN);
        Shizuku.addBinderDeadListener(() -> {
            sService = null;
            sBinding = false;
            notifyChanged();
        }, MAIN);
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                tryBind();
            }
            notifyChanged();
        }, MAIN);
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            sBinding = false;
            if (binder != null && binder.pingBinder()) {
                sService = IForceUseService.Stub.asInterface(binder);
                sFailed = false;
            }
            notifyChanged();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sService = null;
            sBinding = false;
            notifyChanged();
        }
    };

    private static boolean binderAlive() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void tryBind() {
        if (!sInitialized || sService != null || sBinding || !binderAlive() || !hasPermission()) {
            return;
        }
        sBinding = true;
        try {
            Shizuku.bindUserService(sArgs, CONNECTION);
        } catch (Throwable t) {
            sBinding = false;
        }
    }

    static boolean isInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static int state(Context c) {
        if (!isSupported()) {
            return STATE_UNSUPPORTED;
        }
        init(c);
        if (sService != null) {
            return sFailed ? STATE_FAILED : STATE_READY;
        }
        if (!binderAlive()) {
            return isInstalled(c) ? STATE_NOT_RUNNING : STATE_NOT_INSTALLED;
        }
        if (!hasPermission()) {
            return STATE_NO_PERMISSION;
        }
        tryBind();
        return STATE_CONNECTING;
    }

    /** Helper connected and its last force-use call (if any) worked. */
    static boolean isReady() {
        return sService != null && !sFailed;
    }

    /** Return value of the last setForceUse call made by the helper (0 = success). */
    static int lastResult() {
        return sLastResult;
    }

    /**
     * Forces media to the speaker (or clears our force), verified by reading it back.
     *
     * @return true if the requested state is in place.
     */
    static boolean setForceMedia(boolean speaker) {
        IForceUseService s = sService;
        if (s == null) {
            return false;
        }
        boolean ok;
        try {
            int current = s.getForceMedia();
            if (speaker && current == FORCE_SPEAKER) {
                ok = true;
            } else if (!speaker && current != FORCE_SPEAKER) {
                // Not ours (or already cleared): leave e.g. FORCE_NO_BT_A2DP untouched.
                return true;
            } else {
                sLastResult = s.setForceMedia(speaker);
                ok = sLastResult == 0 && s.getForceMedia() == (speaker ? FORCE_SPEAKER : FORCE_NONE);
            }
        } catch (RemoteException | RuntimeException e) {
            sService = null;
            notifyChanged();
            return false;
        }
        if (speaker && sFailed == ok) {
            sFailed = !ok;
            notifyChanged();
        }
        return ok;
    }

    static void requestPermission() {
        if (binderAlive()) {
            try {
                Shizuku.requestPermission(REQUEST_CODE);
            } catch (Throwable ignored) {
                // Shizuku went away meanwhile; the state text shows it.
            }
        }
    }

    /** Opens the Shizuku app, or its store page if it is not installed. */
    static void openShizuku(Activity a) {
        Intent launch = a.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
        if (launch != null) {
            try {
                a.startActivity(launch);
                return;
            } catch (RuntimeException ignored) {
                // fall through to the store page
            }
        }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + SHIZUKU_PACKAGE)));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/download/")));
            } catch (RuntimeException ignored) {
                // No browser.
            }
        }
    }
}
