package com.ahmadman.notifsaver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.Person;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import com.ahmadman.notifsaver.core.Capture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/** Real Notification objects go through the listener service into the SQLite archive. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "tr")
public class PipelineTest {

    static final String CHAT = "com.example.chat";
    static final String MAIL = "com.example.mail";
    static final long T0 = 1_762_250_000_000L; // 4 Nov 2025, about 10:53 in Istanbul

    private Application app;
    private ListenerService service;
    private Store store;
    private int nextId;

    @Before
    public void setUp() {
        Prefs.reset();
        Store.reset();
        app = RuntimeEnvironment.getApplication();
        install(CHAT, "Sohbet");
        install(MAIL, "Posta");
        service = Robolectric.buildService(ListenerService.class).create().get();
        store = Store.get(app);
    }

    @After
    public void tearDown() {
        Store.reset();
        Prefs.reset();
    }

    private void install(String pkg, String label) {
        PackageInfo pi = new PackageInfo();
        pi.packageName = pkg;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.packageName = pkg;
        pi.applicationInfo.nonLocalizedLabel = label;
        shadowOf(app.getPackageManager()).installPackage(pi);
    }

    private void post(String pkg, int id, Notification n, long when) throws InterruptedException {
        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, id, null, 10_000 + id, 0, 0, n,
                Process.myUserHandle(), when);
        service.onNotificationPosted(sbn);
        store.flush();
    }

    private Notification.Builder builder() {
        return new Notification.Builder(app, "c").setSmallIcon(android.R.drawable.sym_def_app_icon);
    }

    private static Person person(String name) {
        return new Person.Builder().setName(name).build();
    }

    private Notification chat(String title, String group, Object... messages) {
        Notification.MessagingStyle style = new Notification.MessagingStyle(person("Ben"));
        if (group != null) {
            style.setConversationTitle(group);
            style.setGroupConversation(true);
        }
        for (int i = 0; i < messages.length; i += 3) {
            String sender = (String) messages[i + 2];
            style.addMessage(new Notification.MessagingStyle.Message((String) messages[i], (Long) messages[i + 1],
                    sender == null ? null : person(sender)));
        }
        return builder().setContentTitle(title).setStyle(style).build();
    }

    @Test
    public void chatUpdatesSaveEveryMessageOnceInOrder() throws Exception {
        post(CHAT, 1, chat("Ali", null, "Selam", T0, "Ali"), T0);
        // the app re-posts the whole conversation with each new message
        post(CHAT, 1, chat("Ali", null, "Selam", T0, "Ali", "Nasılsın?", T0 + 4_000, "Ali"), T0 + 4_000);
        post(CHAT, 1, chat("Ali", null, "Selam", T0, "Ali", "Nasılsın?", T0 + 4_000, "Ali",
                "İyiyim", T0 + 9_000, null), T0 + 9_000);

        List<Capture> thread = store.db.thread(CHAT, "Ali", 100);
        assertEquals(3, thread.size());
        assertEquals("Selam", thread.get(0).text);
        assertEquals("Nasılsın?", thread.get(1).text);
        assertEquals("İyiyim", thread.get(2).text);
        assertEquals("Ali", thread.get(0).sender);
        assertEquals("Siz", thread.get(2).sender); // own reply has no sender
        assertEquals(T0 + 4_000, thread.get(1).time); // the message's own time, to the second
        assertEquals("Sohbet", thread.get(0).app);
        assertTrue(thread.get(0).message);
        assertEquals(3, store.db.count());

        List<Db.Group> apps = store.db.apps();
        assertEquals(1, apps.size());
        assertEquals(3, apps.get(0).count);
        assertEquals("İyiyim", apps.get(0).last.text);
    }

    @Test
    public void groupChatsKeepSendersAndConversationTitle() throws Exception {
        post(CHAT, 2, chat("Aile (2 mesaj)", "Aile (2 mesaj)", "Akşam yemeği 8de", T0, "Annem",
                "Tamam", T0 + 1_000, "Ayşe"), T0 + 1_000);
        List<Db.Group> convs = store.db.conversations(CHAT);
        assertEquals(1, convs.size());
        assertEquals("Aile", convs.get(0).conversation); // "(2 mesaj)" counter removed
        List<Capture> thread = store.db.thread(CHAT, "Aile", 10);
        assertEquals("Annem", thread.get(0).sender);
        assertEquals("Ayşe", thread.get(1).sender);
    }

    @Test
    public void deletedMessageKeepsTheSavedOriginal() throws Exception {
        post(CHAT, 3, chat("Ali", null, "Gizli bilgi: 1234", T0, "Ali"), T0);
        post(CHAT, 3, chat("Ali", null, "Bu mesaj silindi", T0, "Ali"), T0 + 60_000);

        List<Capture> deleted = store.db.deletedMessages(10);
        assertEquals(1, deleted.size());
        assertEquals("Gizli bilgi: 1234", deleted.get(0).text);
        assertTrue(deleted.get(0).recovered);
        assertEquals(1, store.db.deletedCount());

        List<Capture> thread = store.db.thread(CHAT, "Ali", 10);
        assertEquals(2, thread.size());
        assertTrue(thread.get(0).recovered ^ thread.get(1).recovered);
        assertTrue(thread.get(0).deleted || thread.get(1).deleted);
    }

    @Test
    public void deletedMarkerWithoutOriginalIsListedToo() throws Exception {
        post(CHAT, 4, chat("Veli", null, "This message was deleted", T0, "Veli"), T0);
        List<Capture> deleted = store.db.deletedMessages(10);
        assertEquals(1, deleted.size());
        assertTrue(deleted.get(0).deleted);
        assertFalse(deleted.get(0).recovered);
    }

    @Test
    public void summariesAndOngoingAreSkippedUnlessWanted() throws Exception {
        post(MAIL, 5, builder().setContentTitle("3 yeni e-posta").setContentText("x").setGroup("g")
                .setGroupSummary(true).build(), T0);
        post(MAIL, 6, builder().setContentTitle("Müzik çalıyor").setContentText("Şarkı").setOngoing(true).build(), T0);
        post(MAIL, 7, builder().setContentTitle("İndiriliyor").setContentText("%40").setProgress(100, 40, false).build(), T0);
        assertEquals(0, store.db.count());

        Prefs.get(app).saveOngoing = true;
        post(MAIL, 6, builder().setContentTitle("Müzik çalıyor").setContentText("Şarkı").setOngoing(true).build(), T0);
        assertEquals(1, store.db.count());
        // summaries still repeat their children, so they are never saved
        post(MAIL, 5, builder().setContentTitle("3 yeni e-posta").setContentText("x").setGroup("g")
                .setGroupSummary(true).build(), T0);
        assertEquals(1, store.db.count());
    }

    @Test
    public void bigTextInboxAndRepostWindow() throws Exception {
        Notification big = builder().setContentTitle("Ayşe Yılmaz").setContentText("Toplantı")
                .setStyle(new Notification.BigTextStyle().bigText("Toplantı yarın 10:00'da, salon B. Lütfen geç kalmayın."))
                .build();
        post(MAIL, 8, big, T0);
        post(MAIL, 8, big, T0 + 60_000); // same content re-posted a minute later: duplicate
        assertEquals(1, store.db.count());
        Capture c = store.db.timeline(MAIL, 10, 0).get(0);
        assertEquals("Toplantı", c.text);
        assertEquals("Toplantı yarın 10:00'da, salon B. Lütfen geç kalmayın.", c.body());
        assertEquals(T0, c.time);
        assertFalse(c.message);

        post(MAIL, 8, big, T0 + 11 * 60_000); // after the 10 minute window: a new event
        assertEquals(2, store.db.count());

        Notification inbox = builder().setContentTitle("2 yeni ileti").setContentText("Ali, Veli")
                .setStyle(new Notification.InboxStyle().addLine("Ali: Fatura").addLine("Veli: Rapor")).build();
        post(MAIL, 9, inbox, T0);
        Capture in = store.db.search("rapor", 10, 0).get(0);
        assertEquals("Ali: Fatura\nVeli: Rapor", in.body());
    }

    @Test
    public void appFilterBothModesAndNeverItself() throws Exception {
        Prefs prefs = Prefs.get(app);
        prefs.setOn(MAIL, false); // "all apps" mode, mail switched off
        post(MAIL, 10, builder().setContentTitle("Posta").setContentText("a").build(), T0);
        post(CHAT, 11, builder().setContentTitle("Sohbet").setContentText("b").build(), T0);
        post(app.getPackageName(), 12, builder().setContentTitle("Ben").setContentText("c").build(), T0);
        assertEquals(1, store.db.count());
        assertEquals(CHAT, store.db.timeline(null, 10, 0).get(0).pkg);

        prefs.onlySelected = true; // only chosen apps
        prefs.setOn(MAIL, true);
        post(MAIL, 13, builder().setContentTitle("Posta 2").setContentText("d").build(), T0);
        post(CHAT, 14, builder().setContentTitle("Sohbet 2").setContentText("e").build(), T0);
        assertEquals(2, store.db.count());
        assertTrue(prefs.isOn(MAIL));
        assertFalse(prefs.isOn(CHAT));
    }

    @Test
    public void searchIgnoresTurkishLettersCaseAndWildcards() throws Exception {
        post(MAIL, 15, builder().setContentTitle("ŞÜKRÜ IŞIK").setContentText("Çarşıda görüşürüz").build(), T0);
        post(MAIL, 16, builder().setContentTitle("İndirim").setContentText("%50 indirim_kodu").build(), T0);
        assertEquals(1, store.db.search("sukru isik", 10, 0).size());
        assertEquals(1, store.db.search("CARSIDA", 10, 0).size());
        assertEquals(1, store.db.search("indirim_k", 10, 0).size());
        assertEquals(1, store.db.search("%5", 10, 0).size());
        assertEquals(0, store.db.search("_x", 10, 0).size());
        assertEquals(2, store.db.search("posta", 10, 0).size()); // app name is searchable too
    }

    @Test
    public void retentionDeletesOldEntries() throws Exception {
        long now = System.currentTimeMillis();
        post(MAIL, 17, builder().setContentTitle("Eski").setContentText("a").build(), now - 10 * 86_400_000L);
        post(MAIL, 18, builder().setContentTitle("Yeni").setContentText("b").build(), now - 3_600_000L);
        Prefs.get(app).retentionDays = 7;
        store.cleanup();
        store.flush();
        assertEquals(1, store.db.count());
        assertEquals("Yeni", store.db.timeline(null, 10, 0).get(0).title);
    }

    @Test
    public void deletesAndExportRows() throws Exception {
        post(CHAT, 19, chat("Ali", null, "a", T0, "Ali", "b", T0 + 1, "Ali"), T0);
        post(MAIL, 20, builder().setContentTitle("Posta").setContentText("c").build(), T0);
        final int[] seen = {0};
        store.db.forEach(c -> seen[0]++);
        assertEquals(3, seen[0]);
        store.write(() -> store.db.deleteConversation(CHAT, "Ali"));
        store.flush();
        assertEquals(1, store.db.count());
        Capture left = store.db.timeline(null, 1, 0).get(0);
        assertEquals(left.id, store.db.get(left.id).id);
        store.write(() -> store.db.deleteOne(left.id));
        store.flush();
        assertEquals(0, store.db.count());
        assertNull(store.db.get(left.id));
    }

    @Test
    public void activeNotificationsAreImportedOnConnect() throws Exception {
        // what is already in the shade when the listener connects is saved too
        org.robolectric.shadows.ShadowNotificationListenerService shadow = org.robolectric.shadow.api.Shadow.extract(service);
        shadow.addActiveNotification(new StatusBarNotification(MAIL, MAIL, 21, null, 10_021, 0, 0,
                builder().setContentTitle("Bekleyen").setContentText("Okunmamış").build(), Process.myUserHandle(), T0));
        assertEquals(0, store.db.count());
        service.onListenerConnected();
        store.flush();
        assertEquals(1, store.db.count());
        assertEquals("Bekleyen", store.db.timeline(null, 1, 0).get(0).title);
        assertTrue(ListenerService.connected);
        service.onListenerDisconnected();
        assertFalse(ListenerService.connected);
    }
}
