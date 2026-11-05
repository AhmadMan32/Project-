package com.ahmadman.podpil;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import com.ahmadman.podpil.core.Aap;
import com.ahmadman.podpil.core.Crypto;
import com.ahmadman.podpil.core.Estimator;
import com.ahmadman.podpil.core.Proximity;
import com.ahmadman.podpil.core.Status;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Runs in the background: notices when AirPods connect, reads their battery from the Bluetooth
 * broadcast (and exactly over the accessory channel where the phone allows it), keeps the
 * notification up to date, warns on low battery and shows the pop-up.
 */
@SuppressLint("MissingPermission") // every entry point checks Perms.bluetooth first
public class PodService extends Service implements AapClient.Callback {

    static final String CH_STATUS = "status";
    static final String CH_ALERT = "alerts";
    static final int ID_STATUS = 1;
    static final int ID_ALERT = 2;
    /** Other people's AirPods are further away than this. */
    static final int MIN_RSSI = -65;
    static final int WEAK_RSSI = -85;
    /** Service UUID that AirPods and Beats offer over classic Bluetooth. */
    static final UUID APPLE_AAP = UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a");

    /** Off in unit tests (no real Bluetooth sockets there). */
    static boolean tryAap = true;
    private static volatile PodService running;
    private static volatile boolean uiVisible;

    static void start(Context c) {
        if (!Perms.bluetooth(c)) {
            return;
        }
        try {
            Intent i = new Intent(c, PodService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (RuntimeException e) {
            android.util.Log.w("PodPil", "cannot start service", e);
        }
    }

    static void setUiVisible(boolean visible) {
        uiVisible = visible;
        PodService s = running;
        if (s != null) {
            s.main.post(s::updateScan);
        }
    }

    /** The keys were changed in the settings. */
    static void keysChanged() {
        PodService s = running;
        if (s != null) {
            s.main.post(s::loadKeys);
        }
    }

    static PodService instance() {
        return running;
    }

    final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private int scanMode = -1;
    private long scanStartedAt;
    private BluetoothProfile a2dp;
    private BluetoothProfile headset;
    private final Set<String> a2dpDevices = new HashSet<>();
    private final Set<String> hfpDevices = new HashSet<>();
    private BluetoothDevice apple;
    private long connectedAt;
    private boolean popupPending;
    private long popupUntil;

    private Status ble;
    private long bleAt;
    private boolean bleOwn;
    private long lastOwnAt;
    private Status aap;
    private AapClient aapClient;
    private int aapAttempts;
    private byte[] irk;
    private byte[] enc;

    private Estimator.Learner learner;
    private int learnerModel = -1;
    private long lastNotify;
    private String lastNotifyText;
    private boolean alerted;
    private long lastSaved;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (PodState.status != null) {
                publish(now, false);
            } else {
                notifyStatus(true);
            }
            updateScan();
            main.postDelayed(this, 15_000);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult r) {
            handle(r);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                handle(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            android.util.Log.w("PodPil", "scan failed " + errorCode);
            scanning = false;
            main.postDelayed(PodService.this::updateScan, 10_000);
        }

        private void handle(ScanResult r) {
            ScanRecord rec = r.getScanRecord();
            byte[] mfg = rec == null ? null : rec.getManufacturerSpecificData(Proximity.APPLE);
            if (mfg != null) {
                onAdvertisement(r.getDevice().getAddress(), r.getRssi(), mfg, System.currentTimeMillis());
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);
                PodState.bluetoothOn = state == BluetoothAdapter.STATE_ON;
                if (!PodState.bluetoothOn) {
                    a2dpDevices.clear();
                    hfpDevices.clear();
                    deviceGone(null);
                    scanning = false;
                    scanner = null;
                }
                updateScan();
                PodState.changed();
                return;
            }
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) {
                return;
            }
            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                a2dpDevices.remove(d.getAddress());
                hfpDevices.remove(d.getAddress());
                audioChanged(d, false);
                return;
            }
            int state = i.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED);
            Set<String> set = BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action) ? a2dpDevices : hfpDevices;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                set.add(d.getAddress());
                audioChanged(d, true);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                set.remove(d.getAddress());
                audioChanged(d, a2dpDevices.contains(d.getAddress()) || hfpDevices.contains(d.getAddress()));
            }
        }
    };

    private final BluetoothProfile.ServiceListener profiles = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy;
            } else {
                headset = proxy;
            }
            try {
                for (BluetoothDevice d : proxy.getConnectedDevices()) {
                    (profile == BluetoothProfile.A2DP ? a2dpDevices : hfpDevices).add(d.getAddress());
                    audioChanged(d, true);
                }
            } catch (RuntimeException ignored) {
                // permission revoked meanwhile
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null;
            } else {
                headset = null;
            }
        }
    };

    // ---------------------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannels(this);
        if (!foreground()) {
            stopSelf();
            return;
        }
        running = this;
        loadKeys();
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        PodState.bluetoothOn = adapter != null && adapter.isEnabled();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_EXPORTED); // sent by the system Bluetooth app
        } else {
            registerReceiver(receiver, f);
        }
        if (adapter != null) {
            try {
                adapter.getProfileProxy(this, profiles, BluetoothProfile.A2DP);
                adapter.getProfileProxy(this, profiles, BluetoothProfile.HEADSET);
            } catch (RuntimeException e) {
                android.util.Log.w("PodPil", "profile proxy", e);
            }
        }
        if (PodState.status == null) {
            Status last = prefs.last();
            if (last != null) {
                PodState.status = last; // shown as "last seen" until fresh data arrives
            }
        }
        updateScan();
        main.postDelayed(tick, 15_000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        main.removeCallbacksAndMessages(null);
        stopScan();
        closeAap();
        Popup.hide();
        try {
            unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // never registered
        }
        if (adapter != null) {
            if (a2dp != null) {
                adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            }
            if (headset != null) {
                adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
            }
        }
        PodState.audioConnected = false;
        PodState.connectedName = null;
        PodState.aapLive = false;
        PodState.changed();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean foreground() {
        try {
            Notification n = statusNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(ID_STATUS, n);
            }
            return true;
        } catch (RuntimeException e) {
            android.util.Log.w("PodPil", "startForeground", e);
            return false;
        }
    }

    void loadKeys() {
        irk = prefs.irk();
        enc = prefs.encKey();
    }

    // ---------------------------------------------------------------------------------------
    // Connection

    static boolean isApple(BluetoothDevice d) {
        try {
            String name = d.getName();
            if (name != null) {
                String n = name.toLowerCase(Locale.ROOT);
                if (n.contains("airpods") || n.contains("beats")) {
                    return true;
                }
            }
            ParcelUuid[] uuids = d.getUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (APPLE_AAP.equals(u.getUuid())) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // permission missing
        }
        return false;
    }

    private void audioChanged(BluetoothDevice d, boolean connected) {
        PodState.audioConnected = !a2dpDevices.isEmpty() || !hfpDevices.isEmpty();
        if (connected && apple == null && isApple(d)) {
            apple = d;
            connectedAt = System.currentTimeMillis();
            PodState.connectedName = name(d);
            popupPending = prefs.popup();
            popupUntil = connectedAt + 20_000;
            aapAttempts = 0;
            startAap();
            if (PodState.status != null && PodState.live(connectedAt)) {
                maybePopup(connectedAt);
            }
        } else if (!connected && apple != null && apple.getAddress().equals(d.getAddress())) {
            deviceGone(d);
        }
        updateScan();
        notifyStatus(true);
        PodState.changed();
    }

    private void deviceGone(BluetoothDevice d) {
        if (apple == null) {
            return;
        }
        apple = null;
        closeAap();
        popupPending = false;
        Popup.hide();
        PodState.connectedName = null;
        Status s = PodState.status;
        if (s != null) {
            prefs.last(s, name(d));
        }
    }

    private static String name(BluetoothDevice d) {
        try {
            return d == null ? null : d.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accessory channel (exact values)

    private void startAap() {
        if (!tryAap || apple == null || aapClient != null || aapAttempts >= 3) {
            return;
        }
        aapAttempts++;
        aapClient = new AapClient(apple, this);
        aapClient.start();
    }

    private void closeAap() {
        if (aapClient != null) {
            aapClient.close();
            aapClient = null;
        }
        aap = null;
        PodState.aapLive = false;
    }

    @Override
    public void onAapReady(AapClient c) {
        main.post(() -> {
            if (c != aapClient) {
                return;
            }
            PodState.aapLive = true;
            aapAttempts = 0;
            prefs.aapState(Prefs.AAP_OK);
            PodState.changed();
        });
    }

    @Override
    public void onAapFrame(AapClient c, byte[] frame) {
        main.post(() -> {
            if (c == aapClient) {
                onAapFrame(frame, System.currentTimeMillis());
            }
        });
    }

    /** One frame from the accessory channel (package-private for tests). */
    void onAapFrame(byte[] frame, long now) {
        switch (Aap.kind(frame)) {
            case BATTERY:
                Status a = aap == null ? new Status() : aap;
                if (Aap.battery(frame, a)) {
                    PodState.aapLive = true;
                    aap = a;
                    publish(now, true);
                }
                break;
            case KEYS:
                Aap.Keys k = Aap.keys(frame);
                if (k != null) {
                    prefs.keys(k.irk, k.enc);
                    loadKeys();
                    PodState.changed();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onAapClosed(AapClient c, boolean wasReady) {
        main.post(() -> {
            if (c != aapClient) {
                return;
            }
            aapClient = null;
            aap = null;
            PodState.aapLive = false;
            if (!wasReady && aapAttempts >= 3) {
                prefs.aapState(Prefs.AAP_FAILED);
            }
            if (apple != null && aapAttempts < 3) {
                main.postDelayed(this::startAap, wasReady ? 3_000 : 8_000);
            }
            publish(System.currentTimeMillis(), false);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Broadcast (always available)

    private void updateScan() {
        boolean want = adapter != null && adapter.isEnabled() && Perms.bluetooth(this)
                && (PodState.audioConnected || uiVisible || Popup.isShown());
        if (!want) {
            stopScan();
            return;
        }
        long now = System.currentTimeMillis();
        int mode = uiVisible || Popup.isShown() || now - connectedAt < 60_000
                ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_LOW_POWER;
        if (scanning && mode == scanMode) {
            return;
        }
        // Android refuses more than 5 scan starts in 30 seconds
        long since = SystemClock.elapsedRealtime() - scanStartedAt;
        if (scanning && since < 8_000) {
            main.postDelayed(this::updateScan, 8_000 - since);
            return;
        }
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(Proximity.APPLE, new byte[]{0x07, 0x19}, new byte[]{(byte) 0xFF, (byte) 0xFF})
                .build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(mode).setReportDelay(0).build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            scanMode = mode;
            scanStartedAt = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {
            android.util.Log.w("PodPil", "startScan", e);
            scanning = false;
        }
    }

    private void stopScan() {
        if (scanning && scanner != null && adapter != null && adapter.isEnabled()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Bluetooth turned off meanwhile
            }
        }
        scanning = false;
        scanMode = -1;
    }

    /** One broadcast packet (package-private for tests). */
    void onAdvertisement(String address, int rssi, byte[] mfg, long now) {
        Status s = Proximity.parse(mfg);
        if (s == null) {
            return;
        }
        s.rssi = rssi;
        s.address = address;
        s.time = now;
        boolean own = irk != null && Crypto.ownAddress(address, irk);
        if (own) {
            lastOwnAt = now;
        } else {
            boolean same = ble != null && address.equals(ble.address);
            // while ours are connected and nothing was heard for 10 s (phone in a pocket), accept weaker signals
            int floor = apple != null && (ble == null || now - bleAt > 10_000) ? WEAK_RSSI : MIN_RSSI;
            if (rssi < floor && !same) {
                return; // someone else's AirPods across the room
            }
            if (irk != null && lastOwnAt != 0 && now - lastOwnAt < 120_000) {
                return; // ours are known and nearby: ignore strangers
            }
            if (ble != null && now - bleAt < 10_000) {
                if (bleOwn) {
                    return;
                }
                if (!address.equals(ble.address) && rssi < ble.rssi - 3) {
                    return; // keep the strongest (the ones in your ears)
                }
            }
        }
        if (enc != null) {
            Crypto.decrypt(s, enc);
        }
        ble = s;
        bleAt = now;
        bleOwn = own;
        publish(now, true);
    }

    // ---------------------------------------------------------------------------------------
    // Merge, estimate, notify

    private void publish(long now, boolean fresh) {
        Status m;
        boolean bleFresh = ble != null && now - bleAt < 60_000;
        boolean aapFresh = aap != null && PodState.aapLive;
        if (!bleFresh && !aapFresh) {
            if (fresh || PodState.status == null) {
                return;
            }
            m = PodState.status; // keep showing, the screen says how old it is
        } else {
            m = bleFresh ? ble.copy() : new Status();
            if (aapFresh) {
                m.left = aap.left;
                m.right = aap.right;
                m.caseLevel = aap.caseLevel;
                m.leftCharging = aap.leftCharging;
                m.rightCharging = aap.rightCharging;
                m.caseCharging = aap.caseCharging;
                m.source = Status.Source.AAP;
                if (!bleFresh) {
                    Status last = ble != null ? ble : PodState.status != null ? PodState.status : prefs.last();
                    m.modelId = last != null ? last.modelId : 0;
                }
            }
            m.time = now;
        }
        int model = m.modelId;
        if (learner == null || learnerModel != model) {
            learner = new Estimator.Learner(prefs.learnedMpp(model), prefs.learnedSamples(model));
            learnerModel = model;
        }
        int use = Estimator.inUseLevel(m);
        if (fresh && apple != null && learner.observe(use, Estimator.ratedMpp(m.model()), now)) {
            prefs.learned(model, learner.mpp, learner.samples);
        }
        PodState.status = m;
        PodState.estimate = Estimator.estimate(m, learner.usable(), learner.since(use), now);
        PodState.changed();
        if (fresh && now - lastSaved > 30_000) {
            lastSaved = now;
            prefs.last(m, PodState.connectedName);
        }
        notifyStatus(false);
        lowBattery(m, use);
        maybePopup(now);
    }

    private void maybePopup(long now) {
        Status s = PodState.status;
        if (popupPending && now < popupUntil && s != null && s.anyKnown() && Perms.overlay(this)) {
            popupPending = false;
            Popup.show(this);
            updateScan();
        }
    }

    private void lowBattery(Status s, int level) {
        int threshold = prefs.lowAlert();
        if (threshold <= 0 || level == Status.UNKNOWN) {
            return;
        }
        if (level > threshold + 5) {
            alerted = false;
            return;
        }
        if (alerted || level > threshold || apple == null) {
            return;
        }
        alerted = true;
        Estimator.Estimate e = PodState.estimate;
        String text = e != null && e.podMinutes >= 0
                ? getString(R.string.alert_text, Texts.pct(this, level), Texts.duration(this, e.podMinutes))
                : Texts.pct(this, level);
        Notification.Builder b = builder(this, CH_ALERT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.alert_title, s.model().name))
                .setContentText(text)
                .setContentIntent(openApp(this))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS);
        notify(ID_ALERT, b.build());
    }

    private void notifyStatus(boolean force) {
        long now = System.currentTimeMillis();
        Notification n = statusNotification();
        String key = String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)) + "|"
                + n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (!force && key.equals(lastNotifyText) && now - lastNotify < 60_000) {
            return;
        }
        if (!force && now - lastNotify < 2_000 && key.equals(lastNotifyText)) {
            return;
        }
        lastNotify = now;
        lastNotifyText = key;
        notify(ID_STATUS, n);
    }

    private void notify(int id, Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(id, n);
            } catch (RuntimeException ignored) {
                // notifications not allowed
            }
        }
    }

    Notification statusNotification() {
        Status s = PodState.status;
        boolean connected = apple != null;
        Notification.Builder b = builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openApp(this))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS);
        if (connected && s != null && s.anyKnown()) {
            b.setContentTitle(Texts.levels(this, s));
            b.setContentText(Texts.estimateLine(this, PodState.estimate));
            b.setSubText(getString(s.precise() ? R.string.source_exact_short : R.string.source_steps_short));
        } else if (connected) {
            b.setContentTitle(PodState.connectedName != null ? PodState.connectedName : getString(R.string.app_name));
            b.setContentText(getString(R.string.notif_reading));
        } else {
            b.setContentTitle(getString(R.string.notif_idle_title));
            b.setContentText(getString(R.string.notif_idle_text));
        }
        return b.build();
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(c, channel);
        }
        Notification.Builder b = new Notification.Builder(c);
        b.setPriority(CH_ALERT.equals(channel) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_LOW);
        return b;
    }

    static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel status = new NotificationChannel(CH_STATUS, c.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, c.getString(R.string.channel_alert),
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(alert);
    }
}
