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
        AtvRemote.reset();
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
        AtvRemote.reset();
        AtvIdentity.testIdentity = null;
        PairActivity.pairPortForTests = com.ahmadman.kenarpanel.atv.Messages.PAIRING_PORT;
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
        for (int i = 0; i < 300; i++) {
            try {
                check.run();
                return;
            } catch (AssertionError e) {
                if (i == 299) {
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

    @Test
    public void androidTvPageKeepsTheCardSizeAndSendsKeys() {
        java.util.List<Integer> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtvRemote.get(app).testSink = code -> {
            sent.add(code);
            return true;
        };
        OverlayService s = service();
        s.openPanel();
        idle(600);
        View card = s.panel.root.findViewById(R.id.remote_card);
        int w = card.getWidth();
        int h = card.getHeight();
        assertTrue(w > 0 && h > 0);

        s.panel.root.findViewById(R.id.tab_atv).performClick();
        idle(600);
        assertEquals("atv", new Prefs(app).tvMode());
        assertEquals(View.VISIBLE, s.panel.root.findViewById(R.id.page_atv).getVisibility());
        assertEquals(View.INVISIBLE, s.panel.root.findViewById(R.id.page_lg).getVisibility());
        assertEquals(w, card.getWidth()); // same box as the LG page
        assertEquals(h, card.getHeight());
        assertTrue(s.panel.root.findViewById(R.id.atv_chip).isActivated()); // connected: green
        assertEquals(View.GONE, s.panel.root.findViewById(R.id.atv_unpaired).getVisibility());

        View power = s.panel.root.findViewById(R.id.atv_power);
        touch(power, MotionEvent.ACTION_DOWN, 5, 5);
        touch(power, MotionEvent.ACTION_UP, 5, 5);
        assertEquals(Integer.valueOf(26), sent.get(sent.size() - 1));
        View home = s.panel.root.findViewById(R.id.atv_home);
        touch(home, MotionEvent.ACTION_DOWN, 5, 5);
        touch(home, MotionEvent.ACTION_UP, 5, 5);
        assertEquals(Integer.valueOf(3), sent.get(sent.size() - 1));

        // volume repeats while held
        View volUp = s.panel.root.findViewById(R.id.atv_vol_up);
        sent.clear();
        touch(volUp, MotionEvent.ACTION_DOWN, 5, 5);
        idle(700);
        touch(volUp, MotionEvent.ACTION_UP, 5, 5);
        int held = sent.size();
        assertTrue("repeats=" + held, held >= 3);
        for (int k : sent) {
            assertEquals(24, k);
        }
        idle(500);
        assertEquals(held, sent.size()); // stops when let go

        // touchpad: swipe right three steps, then tap = select
        TouchpadView pad = s.panel.root.findViewById(R.id.atv_pad);
        assertTrue(pad.getWidth() > 0);
        float step = 26 * app.getResources().getDisplayMetrics().density;
        sent.clear();
        touch(pad, MotionEvent.ACTION_DOWN, 20, 40);
        touch(pad, MotionEvent.ACTION_MOVE, 20 + step * 1.5f, 42);
        touch(pad, MotionEvent.ACTION_MOVE, 20 + step * 3.2f, 44);
        assertTrue(pad.dotX() > 20); // the dot follows the finger
        touch(pad, MotionEvent.ACTION_UP, 20 + step * 3.2f, 44);
        assertEquals(java.util.Arrays.asList(22, 22, 22), sent);
        sent.clear();
        touch(pad, MotionEvent.ACTION_DOWN, 30, 30, 10);
        touch(pad, MotionEvent.ACTION_UP, 31, 31, 80);
        assertEquals(java.util.Collections.singletonList(23), sent);

        // back to LG: same size again, LG keys still infrared
        s.panel.root.findViewById(R.id.tab_lg).performClick();
        idle(600);
        assertEquals(View.VISIBLE, s.panel.root.findViewById(R.id.page_lg).getVisibility());
        assertEquals(View.GONE, s.panel.root.findViewById(R.id.page_atv).getVisibility());
        assertEquals(h, card.getHeight());
        assertEquals("lg", new Prefs(app).tvMode());
    }

    @Test
    public void unpairedAndroidTvOffersPairing() {
        new Prefs(app).tvMode("atv");
        OverlayService s = service();
        s.openPanel();
        idle(600);
        assertEquals(View.VISIBLE, s.panel.root.findViewById(R.id.atv_unpaired).getVisibility());
        assertFalse(s.panel.root.findViewById(R.id.atv_chip).isActivated());
        // the "not paired" cover does not change the card's size either
        View card = s.panel.root.findViewById(R.id.remote_card);
        int h = card.getHeight();
        s.panel.select("lg", false);
        idle(100);
        assertEquals(h, card.getHeight());
        s.panel.select("atv", false);
        idle(100);
        s.panel.root.findViewById(R.id.atv_pair).performClick();
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(PairActivity.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void pairOnceThenTheRemoteConnectsByItself() throws Exception {
        try (com.ahmadman.kenarpanel.atv.FakeTv tv = new com.ahmadman.kenarpanel.atv.FakeTv()) {
            AtvIdentity.testIdentity = null; // the app's own identity, made and stored as on a phone
            PairActivity.pairPortForTests = tv.pairingServer.getLocalPort();
            PairActivity p = Robolectric.buildActivity(PairActivity.class).setup().get();
            p.found("Salon TV", "127.0.0.1", tv.remoteServer.getLocalPort());
            idle(3000); // only one TV: pairing starts by itself
            waitFor(() -> {
                idle(50);
                assertEquals(View.VISIBLE, p.findViewById(R.id.code_box).getVisibility());
            });
            assertNotNull(tv.shownCode);
            ((TextView) p.findViewById(R.id.code)).setText(tv.shownCode); // typing 6 characters submits
            Prefs prefs = new Prefs(app);
            waitFor(() -> {
                idle(50);
                assertTrue(prefs.atvPaired());
            });
            assertEquals("Salon TV", prefs.atvName());
            assertEquals("atv", prefs.tvMode());

            // the panel connects without asking and the key reaches the TV
            AtvRemote atv = AtvRemote.get(app);
            atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_HOME); // sent as soon as connected
            waitFor(() -> {
                idle(50);
                assertEquals(AtvRemote.State.READY, atv.state());
            });
            assertEquals(Integer.valueOf(3), tv.keys.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(atv.key(com.ahmadman.kenarpanel.atv.Messages.KEY_DPAD_DOWN));
            assertEquals(Integer.valueOf(20), tv.keys.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            atv.close();
        }
    }

    @Test
    public void anOutdatedTvServiceIsExplainedWithTheFix() throws Exception {
        try (com.ahmadman.kenarpanel.atv.FakeTv tv = new com.ahmadman.kenarpanel.atv.FakeTv()) {
            tv.mode = com.ahmadman.kenarpanel.atv.FakeTv.Mode.OLD_SERVICE; // like an un-updated Mi Box 4
            AtvIdentity.testIdentity = com.ahmadman.kenarpanel.atv.FakeTv.identity("phone");
            PairActivity.pairPortForTests = tv.pairingServer.getLocalPort();
            PairActivity p = Robolectric.buildActivity(PairActivity.class).setup().get();
            p.pair("Mi Box", "127.0.0.1", 6466);
            TextView status = p.findViewById(R.id.pair_status);
            waitFor(() -> {
                idle(50);
                assertEquals(View.VISIBLE, p.findViewById(R.id.pair_retry).getVisibility());
            });
            String text = status.getText().toString();
            assertTrue(text, text.contains("Android TV Remote Service") && text.contains("Güncelle"));
            assertTrue(text.startsWith("Mi Box"));
            TextView detail = p.findViewById(R.id.pair_detail);
            assertEquals(View.VISIBLE, detail.getVisibility());
            assertTrue(detail.getText().toString().startsWith("Ayrıntı: "));
            assertEquals(View.GONE, p.findViewById(R.id.code_box).getVisibility());

            // updated on the TV: "Try again" pairs
            tv.mode = com.ahmadman.kenarpanel.atv.FakeTv.Mode.CURRENT;
            p.findViewById(R.id.pair_retry).performClick();
            assertEquals(View.GONE, detail.getVisibility());
            waitFor(() -> {
                idle(50);
                assertEquals(View.VISIBLE, p.findViewById(R.id.code_box).getVisibility());
            });
        }
    }

    @Test
    public void arrowSlidesTheRemoteSidewaysUnderTheAppColumn() {
        OverlayService s = service();
        s.openPanel();
        idle(600);
        View card = s.panel.root.findViewById(R.id.remote_card);
        View clip = s.panel.root.findViewById(R.id.remote_clip);
        View body = s.panel.root.findViewById(R.id.remote_body);
        View tab = s.panel.root.findViewById(R.id.remote_fold);
        View arrow = s.panel.root.findViewById(R.id.remote_fold_arrow);
        View apps = s.panel.root.findViewById(R.id.apps_card);
        int fullW = card.getWidth();
        int bodyW = body.getWidth();
        int fullH = card.getHeight();
        int wide = s.panel.travel();
        assertEquals(0f, arrow.getRotation(), 0f); // ‹ : slide away to the left

        tab.performClick();
        idle(60);
        assertEquals(0f, tab.getRotation(), 0f); // the tab itself never turns
        // half way: narrower, the keys keep their size and slide left with the moving edge
        assertEquals(View.VISIBLE, clip.getVisibility());
        assertTrue(clip.getWidth() < bodyW && clip.getWidth() > 0);
        assertEquals(bodyW, body.getWidth());
        assertTrue(body.getTranslationX() < 0);
        idle(600);
        assertEquals(View.GONE, clip.getVisibility());
        assertEquals(180f, arrow.getRotation(), 0f); // › : bring it back
        assertFalse(new Prefs(app).remoteOpen());
        assertTrue("card " + card.getWidth() + " vs " + fullW, card.getWidth() < fullW / 4); // only the tab
        assertEquals(fullH > 0, true);
        int[] a = new int[2];
        int[] t = new int[2];
        apps.getLocationOnScreen(a);
        tab.getLocationOnScreen(t);
        assertTrue(t[0] > a[0] && t[0] - (a[0] + apps.getWidth()) < 60); // right next to the app column
        assertTrue(s.panel.travel() < wide / 2);
        assertTrue(s.panelOpen()); // the panel itself stays open

        // the panel comes back with the remote slid away next time
        s.requestClose();
        idle(600);
        s.onDestroy();
        OverlayService s2 = service();
        assertEquals(View.GONE, s2.panel.root.findViewById(R.id.remote_clip).getVisibility());
        s2.openPanel();
        idle(600);

        // › slides it back out, full size
        View tab2 = s2.panel.root.findViewById(R.id.remote_fold);
        View arrow2 = s2.panel.root.findViewById(R.id.remote_fold_arrow);
        View clip2 = s2.panel.root.findViewById(R.id.remote_clip);
        View card2 = s2.panel.root.findViewById(R.id.remote_card);
        tab2.performClick();
        idle(60);
        assertEquals(View.VISIBLE, clip2.getVisibility());
        idle(700);
        assertEquals(fullW, card2.getWidth());
        assertEquals(fullH, card2.getHeight());
        assertEquals(0f, s2.panel.root.findViewById(R.id.remote_body).getTranslationX(), 0.5f);
        assertEquals(0f, arrow2.getRotation(), 0f);
        assertTrue(new Prefs(app).remoteOpen());
        assertEquals(wide, s2.panel.travel());

        // on the right edge everything is mirrored: the tab is on the left, › slides it away right
        new Prefs(app).right(true);
        OverlayService.settingsChanged();
        LinearLayout c = (LinearLayout) card2;
        assertEquals(R.id.remote_fold, c.getChildAt(0).getId());
        assertEquals(180f, arrow2.getRotation(), 0f);
        tab2.performClick();
        idle(60);
        assertTrue(s2.panel.root.findViewById(R.id.remote_body).getTranslationX() > 0);
        idle(600);
        assertEquals(0f, arrow2.getRotation(), 0f);
    }

    @Test
    public void backgroundIsNeverBlurred() {
        OverlayService s = service();
        s.openPanel();
        idle(600);
        WindowManager.LayoutParams lp = s.panelParams();
        assertEquals(0, lp.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        assertEquals(0, lp.getBlurBehindRadius());
        // only a light dim remains
        assertTrue(s.panel.root.getBackground().getAlpha() > 0);
    }
}
