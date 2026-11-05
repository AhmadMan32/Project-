package com.ahmadman.kenarpanel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.ahmadman.kenarpanel.core.Remotes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowWindowManagerImpl;

import java.time.Duration;
import java.util.List;

/** The handle, the instant panel and the infrared keys, on a real service. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "tr")
public class PanelTest {

    /** Records what would go out of the infrared emitter. */
    static final class FakeIr implements Remote.Emitter {
        volatile boolean has = true;
        volatile int frequency;
        volatile int[] pattern;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public boolean available() {
            return has;
        }

        @Override
        public int[][] ranges() {
            return new int[][]{{30_000, 60_000}};
        }

        @Override
        public void transmit(int f, int[] p) {
            frequency = f;
            pattern = p;
            count.incrementAndGet();
        }

        int getTransmittedFrequency() {
            return frequency;
        }

        int[] getTransmittedPattern() {
            return pattern;
        }
    }

    private Application app;
    private FakeIr ir;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        ShadowSettings.setCanDrawOverlays(true);
        ir = new FakeIr();
        Remote.testEmitter = ir;
        // frames only when time passes, one every 11 ms (90 Hz), as on a 90 Hz screen
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(11));
        install("com.example.chat", "Sohbet");
        install("com.example.video", "Video");
        new Prefs(app).apps(java.util.Arrays.asList("com.example.chat", "com.example.video"));
    }

    @After
    public void tearDown() {
        OverlayService s = OverlayService.instance();
        if (s != null) {
            s.onDestroy();
        }
        Remote.testEmitter = null;
        org.robolectric.shadows.ShadowChoreographer.reset();
    }

    private void install(String pkg, String label) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.nonLocalizedLabel = label;
        shadowOf(app.getPackageManager()).installPackage(pi);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = pkg;
        ri.activityInfo.name = pkg + ".Main";
        ri.activityInfo.applicationInfo = pi.applicationInfo;
        ri.nonLocalizedLabel = label;
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(main, ri);
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ri);
    }

    private OverlayService service() {
        return Robolectric.buildService(OverlayService.class).create().get();
    }

    private List<View> windows() {
        WindowManager wm = app.getSystemService(WindowManager.class);
        ShadowWindowManagerImpl s = Shadow.extract(wm);
        return s.getViews();
    }

    private static long clock = 10_000;

    private static void touch(View v, int action, float x, float y) {
        touch(v, action, x, y, 16);
    }

    /** One touch event, {@code dt} ms after the previous one (for fling speed). */
    private static void touch(View v, int action, float x, float y, long dt) {
        clock += dt;
        MotionEvent e = MotionEvent.obtain(clock - 1000, clock, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    private static void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static void waitFor(Runnable check) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (i == 99) {
                    throw e;
                }
                Thread.sleep(10);
            }
        }
    }

    @Test
    public void handleSitsAtTheChosenEdgeAndDragsVertically() {
        OverlayService s = service();
        assertTrue(OverlayService.isRunning());
        assertEquals(1, windows().size());
        WindowManager.LayoutParams lp = s.handleParams();
        assertEquals(Gravity.TOP | Gravity.LEFT, lp.gravity);
        float density = app.getResources().getDisplayMetrics().density;
        assertEquals(Math.round(96 * density), lp.height);
        int startY = lp.y;

        HandleView h = s.handleView();
        touch(h, MotionEvent.ACTION_DOWN, 10, 500);
        touch(h, MotionEvent.ACTION_MOVE, 10, 560);
        touch(h, MotionEvent.ACTION_MOVE, 10, 700);
        idle(200);
        assertEquals(1f, h.grow(), 0.001f); // held: thicker and brighter
        touch(h, MotionEvent.ACTION_UP, 10, 700);
        idle(600);
        assertEquals(0f, h.grow(), 0.001f); // let go: back to normal
        assertEquals(startY + 200, lp.y);
        assertFalse(s.panelShown()); // a drag does not open the panel
        float saved = new Prefs(app).position();
        assertTrue(saved > 0.32f);

        // locked: dragging does nothing
        new Prefs(app).locked(true);
        touch(h, MotionEvent.ACTION_DOWN, 10, 700);
        touch(h, MotionEvent.ACTION_MOVE, 10, 900);
        touch(h, MotionEvent.ACTION_UP, 10, 900);
        assertEquals(startY + 200, lp.y);

        // right edge
        new Prefs(app).right(true);
        OverlayService.settingsChanged();
        assertEquals(Gravity.TOP | Gravity.RIGHT, lp.gravity);
    }

    @Test
    public void tapOpensWithSpringAndBackClosesAnimated() {
        OverlayService s = service();
        HandleView h = s.handleView();
        touch(h, MotionEvent.ACTION_DOWN, 10, 500);
        touch(h, MotionEvent.ACTION_UP, 11, 501);
        assertTrue(s.panelShown()); // the window is there at once, the animation runs on it
        assertEquals(2, windows().size());
        idle(30);

        float early = s.panel.progress();

        assertTrue("early=" + early, early > 0f && early < 1f); // still moving: really animated
        idle(600);
        assertEquals(1f, s.panel.progress(), 0f);
        assertEquals(View.INVISIBLE, h.getVisibility());
        View content = s.panel.root.findViewById(R.id.content);
        assertEquals(0f, content.getTranslationX(), 0.5f);

        // back key: slides out, then the window goes away
        s.panel.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        s.panel.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertFalse(s.panelOpen());
        assertTrue(s.panelShown());
        idle(600);
        assertFalse(s.panelShown());
        assertEquals(1, windows().size());
        assertEquals(View.VISIBLE, h.getVisibility());
        assertEquals(1f, h.getAlpha(), 0f);
    }

    @Test
    public void panelFollowsTheFingerAndSettles() {
        OverlayService s = service();
        HandleView h = s.handleView();
        int slop = android.view.ViewConfiguration.get(app).getScaledTouchSlop();
        touch(h, MotionEvent.ACTION_DOWN, 5, 500);
        touch(h, MotionEvent.ACTION_MOVE, 5 + slop + 2, 500);
        assertTrue(s.panelShown());
        int travel = s.panel.travel();
        float x0 = 5 + slop + 2;
        View content = s.panel.root.findViewById(R.id.content);

        // 30 % of the way: the panel is exactly where the finger is
        touch(h, MotionEvent.ACTION_MOVE, x0 + travel * 0.3f, 500, 200);
        assertEquals(0.3f, s.panel.progress(), 0.01f);
        assertEquals(-0.7f * travel, content.getTranslationX(), 2f);
        assertTrue(h.getAlpha() < 1f); // the handle fades as the panel comes
        // the cascade: the remote (first into view) is further in than the last app icon
        LinearLayout apps = s.panel.root.findViewById(R.id.apps);
        assertTrue(s.panel.root.findViewById(R.id.remote_header).getAlpha()
                > apps.getChildAt(apps.getChildCount() - 1).getAlpha());

        // slow release before half way: springs back and closes
        touch(h, MotionEvent.ACTION_UP, x0 + travel * 0.3f, 500, 400);
        idle(600);
        assertFalse(s.panelShown());

        // past half way: opens fully
        touch(h, MotionEvent.ACTION_DOWN, 5, 500);
        touch(h, MotionEvent.ACTION_MOVE, x0, 500);
        touch(h, MotionEvent.ACTION_MOVE, x0 + travel * 0.7f, 500, 300);
        touch(h, MotionEvent.ACTION_UP, x0 + travel * 0.7f, 500, 300);
        idle(700);
        assertTrue(s.panelOpen());
        assertEquals(1f, s.panel.progress(), 0f);

        // tap on the dimmed area closes
        touch(s.panel.root, MotionEvent.ACTION_DOWN, 1000, 100);
        touch(s.panel.root, MotionEvent.ACTION_UP, 1000, 100);
        idle(600);
        assertFalse(s.panelShown());

        // quick flick: opens even though the finger only went a quarter of the way
        touch(h, MotionEvent.ACTION_DOWN, 5, 500);
        touch(h, MotionEvent.ACTION_MOVE, x0, 500, 5);
        touch(h, MotionEvent.ACTION_MOVE, x0 + travel * 0.25f, 500, 15);
        touch(h, MotionEvent.ACTION_UP, x0 + travel * 0.25f, 500, 5);
        idle(700);
        assertTrue(s.panelOpen());
    }

    @Test
    public void pullingPastOpenIsARubberBandAndOffMeansInstant() {
        OverlayService s = service();
        HandleView h = s.handleView();
        int slop = android.view.ViewConfiguration.get(app).getScaledTouchSlop();
        touch(h, MotionEvent.ACTION_DOWN, 5, 500);
        touch(h, MotionEvent.ACTION_MOVE, 5 + slop + 2, 500);
        touch(h, MotionEvent.ACTION_MOVE, 5 + slop + 2 + s.panel.travel() * 1.4f, 500, 300);
        assertEquals(1.06f, s.panel.progress(), 0.01f);
        touch(h, MotionEvent.ACTION_UP, 5 + slop + 2 + s.panel.travel() * 1.4f, 500, 300);
        idle(700);
        assertEquals(1f, s.panel.progress(), 0f);
        s.requestClose();
        idle(600);

        new Prefs(app).speed(com.ahmadman.kenarpanel.core.Motion.Speed.OFF);
        s.openPanel();
        assertEquals(1f, s.panel.progress(), 0f); // no animation at all
        s.requestClose();
        assertFalse(s.panelShown());
    }

    @Test
    public void panelShowsChosenAppsAndOpensThem() {
        OverlayService s = service();
        s.openPanel();
        idle(600);
        LinearLayout apps = s.panel.root.findViewById(R.id.apps);
        assertEquals(3, apps.getChildCount()); // two apps + "add"
        assertEquals("Sohbet", apps.getChildAt(0).getContentDescription().toString());
        apps.getChildAt(1).performClick();
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.example.video", started.getComponent() != null ? started.getComponent().getPackageName()
                : started.getPackage());
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        idle(600);
        assertFalse(s.panelShown());

        // order of cards follows the side
        LinearLayout content = s.panel.root.findViewById(R.id.content);
        assertEquals(R.id.apps_card, content.getChildAt(0).getId());
        new Prefs(app).right(true);
        OverlayService.settingsChanged();
        assertEquals(R.id.remote_card, content.getChildAt(0).getId());
    }

    @Test
    public void lgKeysSendOnTouchAndSpring() throws Exception {
        OverlayService s = service();
        s.openPanel();
        idle(600);
        View power = s.panel.root.findViewById(R.id.k_power);
        touch(power, MotionEvent.ACTION_DOWN, 40, 40);
        int[] expected = Remotes.Key.POWER.frame().pattern;
        waitFor(() -> assertArrayEquals(expected, ir.getTransmittedPattern()));
        assertEquals(38_000, ir.getTransmittedFrequency());
        assertTrue(power.isPressed());
        idle(100);
        assertEquals(0.9f, power.getScaleX(), 0.02f); // squeezed
        touch(power, MotionEvent.ACTION_UP, 40, 40);
        assertFalse(power.isPressed());
        idle(600);
        assertEquals(1f, power.getScaleX(), 0.001f); // sprang back

        View hdmi1 = s.panel.root.findViewById(R.id.k_hdmi1);
        touch(hdmi1, MotionEvent.ACTION_DOWN, 10, 10);
        int[] h1 = Remotes.Key.HDMI1.frame().pattern;
        waitFor(() -> assertArrayEquals(h1, ir.getTransmittedPattern()));
        touch(hdmi1, MotionEvent.ACTION_UP, 10, 10);
        View hdmi2 = s.panel.root.findViewById(R.id.k_hdmi2);
        touch(hdmi2, MotionEvent.ACTION_DOWN, 10, 10);
        int[] h2 = Remotes.Key.HDMI2.frame().pattern;
        waitFor(() -> assertArrayEquals(h2, ir.getTransmittedPattern()));
        touch(hdmi2, MotionEvent.ACTION_UP, 10, 10);
        assertEquals(3, ir.count.get()); // one transmission per press
    }

    @Test
    public void withoutEmitterTheRemoteSaysSo() {
        ir.has = false;
        OverlayService s = service();
        assertEquals(View.VISIBLE, s.panel.root.findViewById(R.id.ir_missing).getVisibility());
        assertFalse(s.panel.root.findViewById(R.id.k_power).isEnabled());
        assertFalse(s.panel.root.findViewById(R.id.k_hdmi1).isEnabled());
    }

    @Test
    public void settingsScreenAndAppPicker() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(a.getString(R.string.state_off), ((TextView) a.findViewById(R.id.state_title)).getText().toString());
        a.findViewById(R.id.toggle).performClick();
        assertTrue(new Prefs(app).enabled());
        Intent svc = shadowOf(app).getNextStartedService();
        assertNotNull(svc);
        assertEquals(OverlayService.class.getName(), svc.getComponent().getClassName());
        assertEquals(a.getString(R.string.ir_ok), ((TextView) a.findViewById(R.id.ir_status)).getText().toString());
        assertEquals(2, ((LinearLayout) a.findViewById(R.id.apps_preview)).getChildCount());
        ((LinearLayout) a.findViewById(R.id.seg_speed)).getChildAt(1).performClick();
        assertEquals(com.ahmadman.kenarpanel.core.Motion.Speed.MEDIUM, new Prefs(app).speed());
        assertTrue(((TextView) a.findViewById(R.id.refresh_info)).getText().toString().contains("Hz"));
        a.findViewById(R.id.test_hdmi2).performClick();
        int[] h2 = Remotes.Key.HDMI2.frame().pattern;
        try {
            waitFor(() -> assertArrayEquals(h2, ir.getTransmittedPattern()));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        AppPickerActivity p = Robolectric.buildActivity(AppPickerActivity.class).setup().get();
        ListView list = p.findViewById(R.id.list);
        assertEquals(2, list.getAdapter().getCount());
        assertEquals("com.example.chat", ((AppPickerActivity.App) list.getItemAtPosition(0)).pkg);
        list.performItemClick(null, 0, 0);
        assertEquals(java.util.Collections.singletonList("com.example.video"), new Prefs(app).apps());
        list.performItemClick(null, 0, 0);
        assertEquals(java.util.Arrays.asList("com.example.video", "com.example.chat"), new Prefs(app).apps());
    }
}
