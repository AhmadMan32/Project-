package com.ahmadman.disableheadphone;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;
import io.github.muntashirakon.adb.android.AdbMdns;

/**
 * Built-in wireless debugging client (what Shizuku does, inside this app).
 *
 * 1. Pairing (once): the user opens Developer options > Wireless debugging > "Pair device with
 *    pairing code" and types the code into this app's notification. The phone then trusts the
 *    app's key ({@link AdbManager}).
 * 2. Connecting: the app finds the wireless debugging port with mDNS and connects to its own adbd
 *    on 127.0.0.1 with TLS. Right after the first connection it grants itself
 *    WRITE_SECURE_SETTINGS via "pm grant", so after a reboot it can switch wireless debugging back
 *    on by itself (Wi-Fi must be connected).
 * 3. Over that connection it starts {@link ForceCli} with app_process. The helper runs as the
 *    shell user and calls AudioSystem.setForceUse(FOR_MEDIA, FORCE_SPEAKER): media audio goes to
 *    the speaker at normal quality, without the call-like communication mode.
 *
 * All network/ADB work runs on a private worker thread. Listeners are called on the main thread.
 */
final class AdbForce {

    static final int STATE_UNSUPPORTED = 0;
    static final int STATE_NOT_PAIRED = 1;
    static final int STATE_WAITING_CODE = 2;
    static final int STATE_PAIRING = 3;
    static final int STATE_NOT_CONNECTED = 4;
    static final int STATE_CONNECTING = 5;
    static final int STATE_READY = 6;
    static final int STATE_FAILED = 7;

    static final String KEY_CODE = "pairing_code";

    private static final String PREFS = "adb";
    private static final String KEY_PAIRED = "paired";
    private static final String CHANNEL_ID = "adb_pairing";
    private static final int PAIR_NOTIFICATION_ID = 2;
    private static final int FORCE_SPEAKER = 1;
    private static final long TICK_CONNECTED_MS = 5000;
    private static final long TICK_RETRY_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 8000;
    private static final long PAIRING_PORT_WAIT_MS = 10000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static Context sApp;
    private static Handler sWorker;

    private static volatile int sState = STATE_UNSUPPORTED;
    private static volatile String sDetail;
    private static volatile boolean sWanted;
    private static volatile boolean sForced;
    private static volatile boolean sUiActive;
    private static volatile int sPairingPort = -1;
    private static AdbMdns sPairingMdns;

    // Worker thread only.
    private static AdbStream sHelper;
    private static BufferedReader sIn;
    private static OutputStream sOut;
    private static boolean sWeForced;
    private static final StringBuilder sOutput = new StringBuilder();

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            tick();
            if (sWanted || sUiActive || sWeForced) {
                sWorker.postDelayed(this, sHelper != null ? TICK_CONNECTED_MS : TICK_RETRY_MS);
            }
        }
    };

    private AdbForce() {
    }

    /** Wireless debugging exists since Android 11. */
    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    static synchronized void init(Context c) {
        if (sApp != null || !isSupported()) {
            return;
        }
        sApp = c.getApplicationContext();
        HandlerThread t = new HandlerThread("adb");
        t.start();
        sWorker = new Handler(t.getLooper());
        sState = isPaired() ? STATE_NOT_CONNECTED : STATE_NOT_PAIRED;
    }

    static void addListener(Runnable r) {
        LISTENERS.addIfAbsent(r);
    }

    static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    static int state() {
        return sState;
    }

    /** Extra text for the UI (last error), or null. */
    static String detail() {
        return sDetail;
    }

    /** True while the helper has confirmed that media is forced to the speaker. */
    static boolean isForced() {
        return sForced;
    }

    /** The speaker service wants (true) or no longer wants (false) media forced to the speaker. */
    static void setWanted(boolean wanted) {
        if (sApp == null || sWanted == wanted) {
            return;
        }
        sWanted = wanted;
        kick();
    }

    /** While the app screen is visible the connection is kept up to show its state. */
    static void setUiActive(boolean active) {
        if (sApp == null) {
            return;
        }
        sUiActive = active;
        if (active) {
            kick();
        }
    }

    static void retry() {
        kick();
    }

    private static void kick() {
        sWorker.removeCallbacks(TICK);
        sWorker.post(TICK);
    }

    private static SharedPreferences prefs() {
        return sApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isPaired() {
        return sApp != null && prefs().getBoolean(KEY_PAIRED, false);
    }

    private static void setPaired(boolean paired) {
        prefs().edit().putBoolean(KEY_PAIRED, paired).apply();
    }

    private static void setState(int state, String detail) {
        boolean changed = sState != state
                || (detail == null ? sDetail != null : !detail.equals(sDetail));
        sState = state;
        sDetail = detail;
        if (changed) {
            notifyListeners();
        }
    }

    private static void setForced(boolean forced) {
        if (sForced != forced) {
            sForced = forced;
            notifyListeners();
        }
    }

    private static void notifyListeners() {
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) {
                r.run();
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Worker: connect, start the helper, keep the force-use in place
    // ---------------------------------------------------------------------------------------

    private static void tick() {
        if (!isPaired()) {
            closeHelper();
            setForced(false);
            if (sState != STATE_WAITING_CODE && sState != STATE_PAIRING) {
                setState(STATE_NOT_PAIRED, sDetail);
            }
            return;
        }
        if (sHelper == null) {
            if (!sWanted && !sUiActive && !sWeForced) {
                return;
            }
            setState(STATE_CONNECTING, null);
            if (!connectAndStartHelper()) {
                setForced(false);
                return;
            }
        }
        try {
            int current = command("get");
            if (sWanted) {
                if (current != FORCE_SPEAKER) {
                    int status = command("set " + FORCE_SPEAKER);
                    current = command("get");
                    if (current != FORCE_SPEAKER) {
                        setForced(false);
                        setState(STATE_FAILED, sApp.getString(R.string.adb_detail_refused, status));
                        return;
                    }
                }
                sWeForced = true;
                setForced(true);
            } else if (sWeForced) {
                if (current == FORCE_SPEAKER) {
                    command("set 0");
                }
                sWeForced = false;
                setForced(false);
            }
            setState(STATE_READY, null);
        } catch (HelperException e) {
            setForced(false);
            setState(STATE_FAILED, e.getMessage());
        } catch (IOException e) {
            closeHelper();
            disconnect();
            setForced(false);
            setState(STATE_NOT_CONNECTED, null);
        }
    }

    private static boolean connectAndStartHelper() {
        AdbManager m;
        try {
            m = AdbManager.get(sApp);
        } catch (Exception e) {
            setState(STATE_FAILED, e.toString());
            return false;
        }
        try {
            if (!m.isConnected()) {
                maybeEnableWirelessDebugging();
                boolean ok;
                try {
                    ok = m.autoConnect(sApp, CONNECT_TIMEOUT_MS);
                } catch (AdbPairingRequiredException e) {
                    setPaired(false);
                    setState(STATE_NOT_PAIRED, sApp.getString(R.string.adb_detail_unpaired));
                    return false;
                }
                if (!ok) {
                    setState(STATE_NOT_CONNECTED, null);
                    return false;
                }
                grantSecureSettings(m);
            }

            String apk = sApp.getApplicationInfo().sourceDir;
            AdbStream s = m.openStream("exec:CLASSPATH=" + apk + " app_process /system/bin "
                    + ForceCli.class.getName() + " 2>&1");
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.openInputStream(), StandardCharsets.UTF_8));
            OutputStream out = s.openOutputStream();
            sOutput.setLength(0);
            String first = readReply(in);
            if (first == null || !first.startsWith("READY")) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // already closed
                }
                String why = first != null ? first : sOutput.toString().trim();
                setState(STATE_FAILED, sApp.getString(R.string.adb_detail_helper,
                        why.isEmpty() ? "?" : why));
                return false;
            }
            sHelper = s;
            sIn = in;
            sOut = out;
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            disconnect();
            setState(STATE_NOT_CONNECTED, e.getMessage());
            return false;
        }
    }

    /** After a reboot wireless debugging is off; switch it back on if we were allowed to. */
    private static void maybeEnableWirelessDebugging() throws InterruptedException {
        if (!sWanted || sApp.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            if (Settings.Global.getInt(sApp.getContentResolver(), "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(sApp.getContentResolver(), "adb_wifi_enabled", 1);
                Thread.sleep(3000);
            }
        } catch (RuntimeException ignored) {
            // Some ROMs block it; the user can switch it on by hand.
        }
    }

    private static void grantSecureSettings(AdbManager m) {
        if (sApp.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            runGrant(m);
        }
        // Volume keys -> media volume while communication mode is used, unless switched off.
        if (!Prefs.volumeKeysOff(sApp) && !VolumeKeyService.isEnabled(sApp)) {
            VolumeKeyService.setEnabled(sApp, true);
        }
    }

    private static void runGrant(AdbManager m) {
        try (AdbStream s = m.openStream("exec:pm grant " + sApp.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS 2>&1")) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.openInputStream(), StandardCharsets.UTF_8));
            while (in.readLine() != null) {
                // wait for pm to finish
            }
        } catch (Exception ignored) {
            // Optional: without it wireless debugging must be switched on by hand after reboot.
        }
    }

    /** Reads lines until a protocol line; anything else (e.g. a crash trace) is kept. */
    private static String readReply(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("OK") || line.startsWith("ERR") || line.startsWith("READY")) {
                return line;
            }
            if (sOutput.length() < 400 && !line.isEmpty()) {
                sOutput.append(line).append(' ');
            }
        }
        return null;
    }

    private static int command(String cmd) throws IOException, HelperException {
        if (sOut == null) {
            throw new IOException("no helper");
        }
        sOut.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        sOut.flush();
        String reply = readReply(sIn);
        if (reply == null) {
            throw new IOException("helper exited");
        }
        if (reply.startsWith("OK")) {
            try {
                return Integer.parseInt(reply.substring(2).trim());
            } catch (NumberFormatException e) {
                throw new HelperException(reply);
            }
        }
        throw new HelperException(reply);
    }

    private static void closeHelper() {
        if (sHelper != null) {
            try {
                sHelper.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
        sHelper = null;
        sIn = null;
        sOut = null;
    }

    private static void disconnect() {
        try {
            AdbManager.get(sApp).disconnect();
        } catch (Exception ignored) {
            // not connected
        }
    }

    private static final class HelperException extends Exception {
        HelperException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Pairing
    // ---------------------------------------------------------------------------------------

    /** Starts waiting for a pairing code: notification with a text field + Developer options. */
    static void startPairing(Activity a) {
        if (!isSupported()) {
            return;
        }
        init(a);
        sPairingPort = -1;
        startPairingDiscovery();
        setState(STATE_WAITING_CODE, null);
        showPairingNotification(sApp.getString(R.string.pair_notif_text));
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Developer options not enabled yet: the instructions explain how.
        }
    }

    static void cancelPairing() {
        if (sApp == null) {
            return;
        }
        stopPairingDiscovery();
        nm().cancel(PAIR_NOTIFICATION_ID);
        if (sState == STATE_WAITING_CODE || sState == STATE_PAIRING) {
            setState(isPaired() ? STATE_NOT_CONNECTED : STATE_NOT_PAIRED, null);
        }
    }

    /**
     * Called by {@link PairingReceiver} with the code typed into the notification.
     * {@code done} runs when pairing has finished (keeps the broadcast, and so the process, alive).
     */
    static void submitCode(Context c, String code, Runnable done) {
        init(c);
        // The app may have been frozen in the background while the user was in Settings, so
        // look for the pairing service again (the pairing dialog is still open at this point).
        if (sPairingPort <= 0) {
            startPairingDiscovery();
        }
        sWorker.post(() -> {
            try {
                pair(code.trim());
            } finally {
                done.run();
            }
        });
    }

    private static void startPairingDiscovery() {
        if (sPairingMdns != null) {
            sPairingMdns.stop();
        }
        sPairingMdns = new AdbMdns(sApp, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (host, port) -> {
            if (port > 0) {
                sPairingPort = port;
            }
        });
        sPairingMdns.start();
    }

    private static void pair(String code) {
        setState(STATE_PAIRING, null);
        showPairingNotification(sApp.getString(R.string.pair_notif_working));
        long deadline = System.currentTimeMillis() + PAIRING_PORT_WAIT_MS;
        while (sPairingPort <= 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        int port = sPairingPort;
        if (port <= 0) {
            String msg = sApp.getString(R.string.pair_no_service);
            setState(STATE_WAITING_CODE, msg);
            showPairingNotification(msg);
            return;
        }
        boolean ok;
        String error = null;
        try {
            ok = AdbManager.get(sApp).pair("127.0.0.1", port, code);
        } catch (Exception e) {
            ok = false;
            error = e.getMessage();
        }
        if (!ok) {
            // A new code dialog uses a new port: look for it again.
            sPairingPort = -1;
            MAIN.post(AdbForce::startPairingDiscovery);
            String msg = sApp.getString(R.string.pair_failed)
                    + (error != null ? " (" + error + ")" : "");
            setState(STATE_WAITING_CODE, msg);
            showPairingNotification(msg);
            return;
        }
        setPaired(true);
        MAIN.post(AdbForce::stopPairingDiscovery);
        nm().cancel(PAIR_NOTIFICATION_ID);
        Notification.Builder b = new Notification.Builder(sApp, ensureChannel())
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(sApp.getString(R.string.pair_done))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setTimeoutAfter(15000);
        nm().notify(PAIR_NOTIFICATION_ID, b.build());
        setState(STATE_CONNECTING, null);
        kick();
    }

    private static void stopPairingDiscovery() {
        if (sPairingMdns != null) {
            sPairingMdns.stop();
            sPairingMdns = null;
        }
    }

    private static NotificationManager nm() {
        return (NotificationManager) sApp.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static String ensureChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                sApp.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm().createNotificationChannel(ch);
        return CHANNEL_ID;
    }

    private static PendingIntent openAppIntent() {
        return PendingIntent.getActivity(sApp, 3,
                new Intent(sApp, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showPairingNotification(String text) {
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel(sApp.getString(R.string.pair_code_label))
                .build();
        PendingIntent reply = PendingIntent.getBroadcast(sApp, 2,
                new Intent(sApp, PairingReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(sApp, R.drawable.ic_speaker),
                sApp.getString(R.string.pair_enter_code), reply)
                .addRemoteInput(input)
                .build();
        Notification n = new Notification.Builder(sApp, ensureChannel())
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle(sApp.getString(R.string.pair_notif_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent())
                .addAction(action)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        nm().notify(PAIR_NOTIFICATION_ID, n);
    }
}
