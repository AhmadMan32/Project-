package com.ahmadman.podpil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.ahmadman.podpil.core.Aap;
import com.ahmadman.podpil.core.Crypto;
import com.ahmadman.podpil.core.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Real broadcast packets and accessory-channel frames go through the service to the screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "tr")
public class ServiceTest {

    private Application app;
    private PodService service;
    private BluetoothDevice pods;

    static byte[] packet(int model, int status, int podsByte, int flagsAndCase, byte[] enc) {
        byte[] d = new byte[27];
        d[0] = 0x07;
        d[1] = 0x19;
        d[2] = 0x01;
        d[3] = (byte) (model >> 8);
        d[4] = (byte) model;
        d[5] = (byte) status;
        d[6] = (byte) podsByte;
        d[7] = (byte) flagsAndCase;
        if (enc != null) {
            System.arraycopy(enc, 0, d, 11, 16);
        }
        return d;
    }

    @Before
    public void setUp() {
        PodState.reset();
        PodService.tryAap = false;
        app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS);
        BluetoothAdapter adapter = app.getSystemService(BluetoothManager.class).getAdapter();
        shadowOf(adapter).setEnabled(true);
        pods = adapter.getRemoteDevice("AA:BB:CC:DD:EE:01");
        shadowOf(pods).setName("Ahmet'in AirPods Pro");
        service = Robolectric.buildService(PodService.class).create().get();
    }

    @After
    public void tearDown() {
        Popup.hide();
        PodState.reset();
    }

    private void connect(boolean on) {
        Intent i = new Intent(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, pods)
                .putExtra(BluetoothProfile.EXTRA_STATE, on ? BluetoothProfile.STATE_CONNECTED : BluetoothProfile.STATE_DISCONNECTED);
        app.sendBroadcast(i);
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Notification statusNotification() {
        return shadowOf(app.getSystemService(NotificationManager.class)).getNotification(PodService.ID_STATUS);
    }

    private static String title(Notification n) {
        return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE));
    }

    private static String text(Notification n) {
        return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    @Test
    public void connectingAirPodsShowsTheirBatteryInTheNotification() {
        assertEquals("AirPods bağlı değil", title(statusNotification()));
        connect(true);
        assertEquals("Ahmet'in AirPods Pro", PodState.connectedName);
        assertTrue(PodState.audioConnected);

        long now = System.currentTimeMillis();
        service.onAdvertisement("11:22:33:44:55:66", -48, packet(0x1420, 0x2A, 0x87, 0x46, null), now);
        Status s = PodState.status;
        assertEquals(70, s.left);
        assertEquals(80, s.right);
        assertEquals(60, s.caseLevel);
        assertEquals(Status.Source.BROADCAST, s.source);

        Notification n = statusNotification();
        assertEquals("Sol %70 · Sağ %80 · Kutu %60 ⚡", title(n));
        assertEquals("≈ 4 sa 12 dk kaldı · kutuyla ≈ 18 sa 36 dk", text(n));
        assertEquals("%10'luk adımlar", String.valueOf(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)));
    }

    @Test
    public void accessoryChannelGivesExactValuesAndKeys() throws Exception {
        connect(true);
        long now = System.currentTimeMillis();
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x88, 0x05, null), now);
        service.onAapFrame(Aap.bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x02, 0x01, 83, 0x02, 0x01, 0x04, 0x01, 87, 0x02, 0x01, 0x08, 0x01, 46, 0x02, 0x01), now + 100);
        Status s = PodState.status;
        assertEquals(87, s.left);
        assertEquals(83, s.right);
        assertEquals(46, s.caseLevel);
        assertEquals(Status.Source.AAP, s.source);
        assertEquals(0x1420, s.modelId); // model still known from the broadcast
        assertTrue(s.leftInEar);
        assertEquals("Sol %87 · Sağ %83 · Kutu %46", title(statusNotification()));
        assertEquals("kesin değer", String.valueOf(statusNotification().extras.getCharSequence(Notification.EXTRA_SUB_TEXT)));

        // the AirPods hand over their keys
        byte[] irk = Crypto.reverse(Crypto.hexKey("ec0234a357c8ad05341010a60a397d9b"));
        byte[] enc = Crypto.hexKey("00112233445566778899aabbccddeeff");
        byte[] frame = new byte[7 + 4 + 16 + 4 + 16];
        System.arraycopy(Aap.bytes(0x04, 0x00, 0x04, 0x00, 0x31, 0x00, 0x02), 0, frame, 0, 7);
        frame[7] = 0x01;
        frame[9] = 16;
        System.arraycopy(irk, 0, frame, 11, 16);
        frame[27] = 0x04;
        frame[29] = 16;
        System.arraycopy(enc, 0, frame, 31, 16);
        service.onAapFrame(frame, now + 200);
        Prefs prefs = new Prefs(app);
        assertNotNull(prefs.encKey());
        assertEquals(Crypto.hex(irk), Crypto.hex(prefs.irk()));

        // later, without the channel: the encrypted broadcast still gives 1 % values
        PodState.aapLive = false;
        byte[] plain = new byte[16];
        plain[1] = 84;
        plain[2] = 81;
        plain[3] = 45;
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(enc, "AES"));
        service.onAdvertisement("70:81:94:0D:FB:AA", -70, packet(0x1420, 0x2A, 0x88, 0x05, c.doFinal(plain)), now + 5000);
        s = PodState.status;
        assertEquals(Status.Source.DECRYPTED, s.source);
        assertEquals(84, s.left);
        assertEquals(81, s.right);
        assertEquals(45, s.caseLevel);

        // own AirPods recognised by their key: a stranger's strong signal is ignored
        service.onAdvertisement("12:34:56:78:9A:BC", -35, packet(0x0F20, 0x2A, 0x22, 0x02, null), now + 6000);
        assertEquals(84, PodState.status.left);
        assertEquals(0x1420, PodState.status.modelId);
    }

    @Test
    public void strangersAndWeakSignalsAreIgnored() {
        connect(true);
        long now = System.currentTimeMillis();
        service.onAdvertisement("11:22:33:44:55:66", -90, packet(0x1420, 0x2A, 0x55, 0x05, null), now);
        assertNull(PodState.status); // far away
        service.onAdvertisement("11:22:33:44:55:66", -45, packet(0x1420, 0x2A, 0x55, 0x05, null), now);
        assertEquals(50, PodState.status.left);
        service.onAdvertisement("22:22:33:44:55:66", -78, packet(0x0F20, 0x2A, 0x11, 0x05, null), now + 300);
        assertEquals(50, PodState.status.left); // weak stranger while ours are heard
        service.onAdvertisement("99:88:77:66:55:44", -60, packet(0x0F20, 0x2A, 0x99, 0x09, null), now + 500);
        assertEquals(50, PodState.status.left); // weaker than ours: someone else's
        service.onAdvertisement("99:88:77:66:55:44", -60, packet(0x0F20, 0x2A, 0x99, 0x09, null), now + 20_000);
        assertEquals(90, PodState.status.left); // ours not seen for a while: strongest wins

        // phone in a pocket: nothing heard for 10 s, a weak signal is accepted while connected
        service.onAdvertisement("33:22:33:44:55:66", -80, packet(0x1420, 0x2A, 0x44, 0x05, null), now + 40_000);
        assertEquals(40, PodState.status.left);
    }

    @Test
    public void lowBatteryWarnsOnceAndDisconnectKeepsLastState() {
        connect(true);
        long now = System.currentTimeMillis();
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x33, 0x05, null), now);
        assertNull(shadowOf(nm).getNotification(PodService.ID_ALERT));
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x22, 0x05, null), now + 1000);
        Notification alert = shadowOf(nm).getNotification(PodService.ID_ALERT);
        assertNotNull(alert);
        assertEquals("AirPods Pro 2 pili azaldı", title(alert));
        assertTrue(text(alert).startsWith("%20 · yaklaşık"));
        nm.cancel(PodService.ID_ALERT);
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x22, 0x05, null), now + 2000);
        assertNull(shadowOf(nm).getNotification(PodService.ID_ALERT)); // only once

        connect(false);
        assertNull(PodState.connectedName);
        assertEquals("AirPods bağlı değil", title(statusNotification()));
        Status last = new Prefs(app).last();
        assertNotNull(last);
        assertEquals(20, last.left);
    }

    @Test
    public void popupAppearsWhenConnectedWithOverlayPermission() {
        ShadowSettings.setCanDrawOverlays(true);
        connect(true);
        assertFalse(Popup.isShown()); // waits for the first battery data
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x87, 0x46, null), System.currentTimeMillis());
        assertTrue(Popup.isShown());
        View v = Popup.current().view();
        assertEquals("Ahmet'in AirPods Pro", ((TextView) v.findViewById(R.id.title)).getText().toString());
        assertTrue(((TextView) v.findViewById(R.id.estimate)).getText().toString().contains("kaldı"));
        Popup.hide();
        assertFalse(Popup.isShown());
    }

    @Test
    public void mainScreenShowsLevelsEstimateAndSource() {
        connect(true);
        long now = System.currentTimeMillis();
        service.onAdvertisement("11:22:33:44:55:66", -50, packet(0x1420, 0x2A, 0x87, 0x46, null), now);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Ahmet'in AirPods Pro", ((TextView) a.findViewById(R.id.device)).getText().toString());
        assertEquals("AirPods Pro 2", ((TextView) a.findViewById(R.id.model)).getText().toString());
        assertEquals("Bağlı · canlı", ((TextView) a.findViewById(R.id.chip)).getText().toString());
        assertEquals("≈ 4 sa 12 dk", ((TextView) a.findViewById(R.id.est_main)).getText().toString());
        assertEquals(View.VISIBLE, a.findViewById(R.id.pods).getVisibility());
        assertEquals(a.getString(R.string.source_steps), ((TextView) a.findViewById(R.id.source)).getText().toString());

        PodsView pv = a.findViewById(R.id.pods);
        pv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        pv.layout(0, 0, 1080, pv.getMeasuredHeight());
        pv.draw(new Canvas(Bitmap.createBitmap(1080, Math.max(1, pv.getMeasuredHeight()), Bitmap.Config.ARGB_8888)));
        assertEquals(pv.levelColor(5, false), app.getColor(R.color.battery_low));
        assertEquals(pv.levelColor(15, false), app.getColor(R.color.battery_mid));
        assertEquals(pv.levelColor(15, true), app.getColor(R.color.battery_ok));
    }
}
