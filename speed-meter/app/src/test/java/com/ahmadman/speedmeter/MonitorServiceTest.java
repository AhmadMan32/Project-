package com.ahmadman.speedmeter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowTrafficStats;

import java.time.Duration;
import java.util.List;

/** Runs the real service: overlay window, sampling every second, notification text. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorServiceTest {

    private Application app;
    private ServiceController<MonitorService> controller;
    private int rx = 1_000_000;
    private int tx = 100_000;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Style.prefs(app).edit().clear().commit();
        MonitorService.SAMPLER.clearTotals();
        ShadowSettings.setCanDrawOverlays(true);
        ShadowTrafficStats.setTotalRxBytes(rx);
        ShadowTrafficStats.setTotalTxBytes(tx);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
    }

    private List<View> overlays() {
        WindowManager wm = (WindowManager) app.getSystemService(android.content.Context.WINDOW_SERVICE);
        org.robolectric.shadows.ShadowWindowManagerImpl shadow = org.robolectric.shadow.api.Shadow.extract(wm);
        return shadow.getViews();
    }

    private void advance(long ms, int rxAdd, int txAdd) {
        rx += rxAdd;
        tx += txAdd;
        ShadowTrafficStats.setTotalRxBytes(rx);
        ShadowTrafficStats.setTotalTxBytes(tx);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); // runs the next 1 s tick
    }

    @Test
    public void showsOverlayMeasuresSpeedAndUpdatesNotification() {
        controller = Robolectric.buildService(MonitorService.class, new Intent()).create().startCommand(0, 1);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(MonitorService.isRunning());
        assertTrue(MonitorService.isOverlayShown());
        List<View> views = overlays();
        assertEquals(1, views.size());
        assertTrue(views.get(0) instanceof SpeedView);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) views.get(0).getLayoutParams();
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, lp.type);
        // locked by default: touches pass through, and opacity stays within Android 12's 80 % limit
        assertTrue((lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue((lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertTrue(lp.alpha <= 0.8f);

        advance(1000, 287_500, 107_375); // 2.3 Mbit/s down, 859 kbit/s up
        assertEquals(287_500, MonitorService.SAMPLER.download(), 1);
        assertEquals(107_375, MonitorService.SAMPLER.upload(), 1);

        NotificationManager nm = app.getSystemService(NotificationManager.class);
        Notification n = shadowOf(nm).getAllNotifications().get(0);
        String title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString();
        assertTrue(title, title.contains("2.3 Mbps") || title.contains("2,3 Mbps"));
        assertTrue(title, title.contains("859 kbps"));
        assertNotNull(n.getSmallIcon());
        assertEquals(android.graphics.drawable.Icon.TYPE_BITMAP, n.getSmallIcon().getType()); // speed drawn in the icon
    }

    @Test
    public void unlockingMakesItDraggableAndStyleChangesApplyLive() {
        controller = Robolectric.buildService(MonitorService.class, new Intent()).create().startCommand(0, 1);
        shadowOf(Looper.getMainLooper()).idle();
        Style s = Style.load(app);
        s.locked = false;
        s.opacity = 100;
        s.x = 200;
        s.y = 300;
        s.save(app);
        shadowOf(Looper.getMainLooper()).idle();
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlays().get(0).getLayoutParams();
        assertEquals(0, lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        assertEquals(1f, lp.alpha, 0.001);
        assertEquals(200, lp.x);
        assertEquals(300, lp.y);
    }

    @Test
    public void withoutOverlayPermissionOnlyTheNotificationRuns() {
        ShadowSettings.setCanDrawOverlays(false);
        controller = Robolectric.buildService(MonitorService.class, new Intent()).create().startCommand(0, 1);
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(MonitorService.isRunning());
        assertFalse(MonitorService.isOverlayShown());
        assertEquals(0, overlays().size());
    }

    @Test
    public void stopActionRemovesTheOverlay() {
        controller = Robolectric.buildService(MonitorService.class, new Intent()).create().startCommand(0, 1);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, overlays().size());
        controller.withIntent(new Intent(app, MonitorService.class).setAction(MonitorService.ACTION_STOP)).startCommand(0, 2);
        controller.destroy();
        controller = null;
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(MonitorService.isRunning());
        assertEquals(0, overlays().size());
        assertFalse(Style.prefs(app).getBoolean("wasRunning", true));
    }
}
