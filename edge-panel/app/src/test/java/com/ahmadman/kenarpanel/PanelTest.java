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
import android.os.SystemClock;
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

    private static void touch(View v, int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, action, x, y, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
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
        touch(h, MotionEvent.ACTION_UP, 10, 700);
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
    public void tapAndInwardSwipeOpenThePanelAtOnce() {
        OverlayService s = service();
        HandleView h = s.handleView();
        touch(h, MotionEvent.ACTION_DOWN, 10, 500);
        touch(h, MotionEvent.ACTION_UP, 11, 501);
        assertTrue(s.panelShown()); // no delay: opened on the same touch
        assertEquals(2, windows().size());
        assertEquals(View.INVISIBLE, h.getVisibility());

        // back key closes
        s.panel.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        s.panel.root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        assertFalse(s.panelShown());
        assertEquals(1, windows().size());
        assertEquals(View.VISIBLE, h.getVisibility());

        // swipe inwards opens while the finger is still moving
        touch(h, MotionEvent.ACTION_DOWN, 5, 500);
        touch(h, MotionEvent.ACTION_MOVE, 120, 505);
        assertTrue(s.panelShown());
        touch(h, MotionEvent.ACTION_UP, 120, 505);

        // tapping the dimmed area closes
        touch(s.panel.root, MotionEvent.ACTION_DOWN, 900, 100);
        touch(s.panel.root, MotionEvent.ACTION_UP, 900, 100);
        assertFalse(s.panelShown());
    }

    @Test
    public void panelShowsChosenAppsAndOpensThem() {
        OverlayService s = service();
        s.openPanel();
        LinearLayout apps = s.panel.root.findViewById(R.id.apps);
        assertEquals(3, apps.getChildCount()); // two apps + "add"
        assertEquals("Sohbet", apps.getChildAt(0).getContentDescription().toString());
        apps.getChildAt(1).performClick();
        assertFalse(s.panelShown());
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.example.video", started.getComponent() != null ? started.getComponent().getPackageName()
                : started.getPackage());
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);

        // order of cards follows the side
        LinearLayout content = s.panel.root.findViewById(R.id.content);
        assertEquals(R.id.apps_card, content.getChildAt(0).getId());
        new Prefs(app).right(true);
        OverlayService.settingsChanged();
        assertEquals(R.id.remote_card, content.getChildAt(0).getId());
    }

    @Test
    public void remoteKeysSendInfraredImmediately() throws Exception {
        new Prefs(app).brand("lg");
        OverlayService s = service();
        s.openPanel();
        View power = s.panel.root.findViewById(R.id.k_power);
        touch(power, MotionEvent.ACTION_DOWN, 5, 5);
        int[] expected = Remotes.LG.frame(Remotes.Key.POWER, false).pattern;
        waitFor(() -> assertArrayEquals(expected, ir.getTransmittedPattern()));
        assertEquals(38_000, ir.getTransmittedFrequency());
        assertTrue(power.isPressed());
        touch(power, MotionEvent.ACTION_UP, 5, 5);
        assertFalse(power.isPressed());

        // brand chip cycles LG -> Sony; Sony has no "back" key
        TextView brand = s.panel.root.findViewById(R.id.brand);
        assertEquals("LG ▾", brand.getText().toString());
        brand.performClick();
        assertEquals("Sony ▾", brand.getText().toString());
        assertEquals("sony", new Prefs(app).brand());
        assertFalse(s.panel.root.findViewById(R.id.k_back).isEnabled());
        View volUp = s.panel.root.findViewById(R.id.k_vol_up);
        touch(volUp, MotionEvent.ACTION_DOWN, 5, 5);
        int[] sony = Remotes.SONY.frame(Remotes.Key.VOL_UP, false).pattern;
        waitFor(() -> assertArrayEquals(sony, ir.getTransmittedPattern()));
        assertEquals(40_000, ir.getTransmittedFrequency());
        // held: keeps repeating (after 350 ms, every 120 ms), stops on release
        int before = ir.count.get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
        waitFor(() -> assertTrue(ir.count.get() >= before + 3));
        touch(volUp, MotionEvent.ACTION_UP, 5, 5);
        Thread.sleep(50);
        int after = ir.count.get();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700));
        Thread.sleep(50);
        assertEquals(after, ir.count.get());

        // digits toggle
        View digits = s.panel.root.findViewById(R.id.digits);
        assertEquals(View.GONE, digits.getVisibility());
        s.panel.root.findViewById(R.id.digits_toggle).performClick();
        assertEquals(View.VISIBLE, digits.getVisibility());
    }

    @Test
    public void withoutEmitterTheRemoteSaysSo() {
        ir.has = false;
        OverlayService s = service();
        assertEquals(View.VISIBLE, s.panel.root.findViewById(R.id.ir_missing).getVisibility());
        assertFalse(s.panel.root.findViewById(R.id.k_power).isEnabled());
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
        ((LinearLayout) a.findViewById(R.id.seg_brand)).getChildAt(1).performClick();
        assertEquals("lg", new Prefs(app).brand());

        AppPickerActivity p = Robolectric.buildActivity(AppPickerActivity.class).setup().get();
        ListView list = p.findViewById(R.id.list);
        assertEquals(2, list.getAdapter().getCount());
        // chosen apps come first, in panel order; untick the first
        assertEquals("com.example.chat", ((AppPickerActivity.App) list.getItemAtPosition(0)).pkg);
        list.performItemClick(null, 0, 0);
        assertEquals(java.util.Collections.singletonList("com.example.video"), new Prefs(app).apps());
        list.performItemClick(null, 0, 0);
        assertEquals(java.util.Arrays.asList("com.example.video", "com.example.chat"), new Prefs(app).apps());
    }
}
