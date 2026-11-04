package com.ahmadman.notifsaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.ahmadman.notifsaver.core.Capture;
import com.ahmadman.notifsaver.core.Rules;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The screens open, show saved data and react to new notifications. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "tr")
public class ScreensTest {

    private Application app;
    private Store store;

    @Before
    public void setUp() {
        Prefs.reset();
        Store.reset();
        app = RuntimeEnvironment.getApplication();
        store = Store.get(app);
    }

    @After
    public void tearDown() {
        Store.reset();
        Prefs.reset();
    }

    private Capture add(String pkg, String app, String conv, String sender, String text, long time, boolean message) {
        Capture c = new Capture();
        c.pkg = pkg;
        c.app = app;
        c.title = conv;
        c.conversation = conv;
        c.sender = sender;
        c.text = text;
        c.time = time;
        c.message = message;
        c.deleted = Rules.isDeletedMarker(text);
        c.hash = Rules.hash(pkg, conv, sender, text, time);
        store.db.insert(c);
        return c;
    }

    /** Lets the background reader finish and its results reach the main thread. */
    private static void settle(Runnable until) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); // also runs the 150 ms debounce
            try {
                until.run();
                return;
            } catch (AssertionError e) {
                if (i == 199) {
                    throw e;
                }
            }
            Thread.sleep(10);
        }
    }

    private static int count(ListView list) {
        ListAdapter a = list.getAdapter();
        return a == null ? 0 : a.getCount() - list.getHeaderViewsCount();
    }

    private void grantAccess(boolean on) {
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        shadowOf(nm).setNotificationListenerAccessGranted(ListenerService.component(app), on);
    }

    @Test
    public void homeShowsSetupUntilAccessIsGranted() throws Exception {
        grantAccess(false);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Activity a = c.get();
        assertEquals(View.VISIBLE, a.findViewById(R.id.setup).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.status).getVisibility());
        a.findViewById(R.id.setup_grant).performClick();
        Intent opened = shadowOf(a).getNextStartedActivity();
        assertTrue(opened.getAction().contains("NOTIFICATION_LISTENER"));

        grantAccess(true);
        c.pause().resume();
        assertEquals(View.GONE, a.findViewById(R.id.setup).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.status).getVisibility());
    }

    @Test
    public void homeListsAppsAndUpdatesLive() throws Exception {
        grantAccess(true);
        long now = System.currentTimeMillis();
        add("com.example.chat", "Sohbet", "Ali", "Ali", "Selam", now - 5_000, true);
        add("com.example.mail", "Posta", "Fatura", null, "Ödeme günü", now - 60_000, false);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ListView list = a.findViewById(R.id.list);
        settle(() -> assertEquals(2, count(list)));
        Db.Group first = (Db.Group) list.getItemAtPosition(list.getHeaderViewsCount());
        assertEquals("com.example.chat", first.pkg); // newest first
        assertEquals("2", ((TextView) a.findViewById(R.id.all_count)).getText().toString());

        // a new notification from another app appears without reopening the screen
        ListenerService service = Robolectric.buildService(ListenerService.class).create().get();
        android.app.Notification n = new android.app.Notification.Builder(app, "c")
                .setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle("Kargo").setContentText("Yolda").build();
        service.onNotificationPosted(new android.service.notification.StatusBarNotification("com.example.shop",
                "com.example.shop", 1, null, 10_001, 0, 0, n, android.os.Process.myUserHandle(), now));
        store.flush();
        settle(() -> assertEquals(3, count(list)));
        assertEquals("3", ((TextView) a.findViewById(R.id.all_count)).getText().toString());

        list.performItemClick(list.getChildAt(list.getHeaderViewsCount()), list.getHeaderViewsCount(), 0);
        Intent opened = shadowOf(a).getNextStartedActivity();
        assertEquals(BrowseActivity.APP, opened.getIntExtra(BrowseActivity.EXTRA_MODE, 0));
    }

    @Test
    public void threadShowsBubblesDayHeaderAndDeletedOriginal() throws Exception {
        long now = System.currentTimeMillis();
        add("com.example.chat", "Sohbet", "Ali", "Ali", "Gizli", now - 9_000, true);
        add("com.example.chat", "Sohbet", "Ali", "Siz", "Tamam", now - 5_000, true);
        add("com.example.chat", "Sohbet", "Ali", "Ali", "Bu mesaj silindi", now - 9_000, true);
        BrowseActivity a = Robolectric.buildActivity(BrowseActivity.class,
                BrowseActivity.thread(app, "com.example.chat", "Ali")).setup().get();
        ListView list = a.findViewById(R.id.list);
        settle(() -> assertEquals(4, count(list))); // "Today" + 3 rows
        assertEquals(a.getString(R.string.today), list.getItemAtPosition(0));
        assertEquals("Ali", ((TextView) a.findViewById(R.id.title)).getText().toString());
        boolean recovered = false;
        for (int i = 1; i < 4; i++) {
            Capture c = (Capture) list.getItemAtPosition(i);
            recovered |= c.recovered && c.text.equals("Gizli");
        }
        assertTrue(recovered);
        // rendering every row type does not crash
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            list.getAdapter().getView(i, null, list);
        }
    }

    @Test
    public void searchFindsAsYouType() throws Exception {
        long now = System.currentTimeMillis();
        add("com.example.mail", "Posta", "Şükrü", null, "Görüşürüz", now, false);
        add("com.example.mail", "Posta", "Banka", null, "Hesap özeti", now - 1000, false);
        BrowseActivity a = Robolectric.buildActivity(BrowseActivity.class,
                BrowseActivity.of(app, BrowseActivity.SEARCH)).setup().get();
        ListView list = a.findViewById(R.id.list);
        TextView empty = a.findViewById(R.id.empty);
        settle(() -> assertEquals(View.VISIBLE, empty.getVisibility()));
        ((TextView) a.findViewById(R.id.query)).setText("sukru");
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
        settle(() -> assertEquals(2, count(list))); // day header + 1 result
        assertEquals(View.GONE, empty.getVisibility());
    }

    @Test
    public void appListTogglesRecording() throws Exception {
        add("com.example.chat", "Sohbet", "Ali", "Ali", "Selam", System.currentTimeMillis(), true);
        AppsActivity a = Robolectric.buildActivity(AppsActivity.class).setup().get();
        ListView list = a.findViewById(R.id.list);
        settle(() -> assertTrue(count(list) >= 1));
        int pos = -1;
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if ("com.example.chat".equals(((AppsActivity.App) list.getItemAtPosition(i)).pkg)) {
                pos = i;
            }
        }
        assertTrue(pos >= 0);
        Prefs prefs = Prefs.get(app);
        assertTrue(prefs.isOn("com.example.chat"));
        list.performItemClick(null, pos, 0);
        assertFalse(prefs.isOn("com.example.chat"));
        assertFalse(prefs.shouldRecord("com.example.chat"));

        android.widget.LinearLayout modes = a.findViewById(R.id.mode);
        modes.getChildAt(1).performClick(); // "only chosen ones"
        assertTrue(prefs.onlySelected);
        assertFalse(prefs.shouldRecord("com.example.other"));
    }

    @Test
    public void settingsAndOtherScreensOpen() throws Exception {
        grantAccess(true);
        SettingsActivity s = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        android.widget.LinearLayout perms = s.findViewById(R.id.permissions);
        assertTrue(perms.getChildCount() >= 4);
        ((android.widget.Switch) s.findViewById(R.id.opt_keep_alive)).setChecked(false);
        assertFalse(Prefs.get(app).keepAlive);
        Robolectric.buildActivity(BrowseActivity.class, BrowseActivity.of(app, BrowseActivity.ALL)).setup();
        Robolectric.buildActivity(BrowseActivity.class, BrowseActivity.of(app, BrowseActivity.DELETED)).setup();
        Robolectric.buildActivity(BrowseActivity.class, BrowseActivity.app(app, "com.example.chat")).setup();
        settle(() -> { });
    }
}
