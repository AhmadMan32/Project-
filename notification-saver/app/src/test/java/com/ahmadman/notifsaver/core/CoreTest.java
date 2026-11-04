package com.ahmadman.notifsaver.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public class CoreTest {

    private static final TimeZone IST = TimeZone.getTimeZone("Europe/Istanbul");

    @Test
    public void recognisesDeletedMessagePlaceholders() {
        assertTrue(Rules.isDeletedMarker("Bu mesaj silindi"));
        assertTrue(Rules.isDeletedMarker("🚫 Bu mesaj silindi."));
        assertTrue(Rules.isDeletedMarker("This message was deleted"));
        assertTrue(Rules.isDeletedMarker("  this message was deleted. "));
        assertFalse(Rules.isDeletedMarker("Bu mesaj silindi mi?"));
        assertFalse(Rules.isDeletedMarker(null));
    }

    @Test
    public void cleansMessageCountsFromTitles() {
        assertEquals("Ahmet", Rules.cleanTitle("Ahmet (3 mesaj)"));
        assertEquals("Ahmet", Rules.cleanTitle("Ahmet (12 messages)"));
        assertEquals("Aile Grubu", Rules.cleanTitle("Aile Grubu: 5 yeni mesaj"));
        assertEquals("Ahmet", Rules.cleanTitle("Ahmet (2 new messages)"));
        assertEquals("Yarın (2 kişi)", Rules.cleanTitle("Yarın (2 kişi)"));
        assertEquals("Ahmet", Rules.cleanTitle(" Ahmet "));
    }

    @Test
    public void appFilterModes() {
        Set<String> sel = new HashSet<>(Collections.singletonList("com.whatsapp"));
        Set<String> exc = new HashSet<>(Collections.singletonList("com.spotify.music"));
        assertTrue(Rules.shouldRecord("com.whatsapp", false, sel, exc, "me"));
        assertTrue(Rules.shouldRecord("org.telegram.messenger", false, sel, exc, "me"));
        assertFalse(Rules.shouldRecord("com.spotify.music", false, sel, exc, "me"));
        assertTrue(Rules.shouldRecord("com.whatsapp", true, sel, exc, "me"));
        assertFalse(Rules.shouldRecord("org.telegram.messenger", true, sel, exc, "me"));
        assertFalse(Rules.shouldRecord("me", false, sel, exc, "me")); // never our own
        assertFalse(Rules.shouldRecord(null, false, sel, exc, "me"));
    }

    @Test
    public void hashesSeparateFieldsSafely() {
        assertEquals(Rules.hash("a", "b", 1L), Rules.hash("a", "b", 1L));
        assertNotEquals(Rules.hash("ab", "c"), Rules.hash("a", "bc"));
        assertNotEquals(Rules.hash("a", null), Rules.hash("a", ""));
        assertEquals(40, Rules.hash("x").length());
    }

    @Test
    public void searchFoldingForTurkish() {
        assertEquals("sukru isik cagri", Rules.fold("ŞÜKRÜ IŞIK Çağrı"));
        assertEquals(Rules.fold("istanbul"), Rules.fold("İSTANBUL"));
        assertEquals("%50\\% indirim%", Rules.likePattern("50% İNDİRİM"));
    }

    @Test
    public void timesAndDayHeaders() {
        long now = 1_793_800_000_000L; // 2026-11-04 (Istanbul)
        assertEquals(8, Formats.time(now, IST).length());
        assertEquals("Bugün", Formats.dayHeader(now - 1000, now, IST, new Locale("tr"), "Bugün", "Dün"));
        assertEquals("Dün", Formats.dayHeader(now - 86_400_000L, now, IST, new Locale("tr"), "Bugün", "Dün"));
        assertTrue(Formats.dayHeader(now - 3 * 86_400_000L, now, IST, new Locale("tr"), "Bugün", "Dün").contains("2026"));
        assertEquals("Dün", Formats.shortWhen(now - 86_400_000L, now, IST, new Locale("tr"), "Dün"));
        assertEquals(5, Formats.shortWhen(now - 60_000, now, IST, new Locale("tr"), "Dün").length());
        assertTrue(Formats.sameDay(now, now - 1000, IST));
    }

    @Test
    public void csvExportQuotesAndKeepsTurkish() {
        Capture c = new Capture();
        c.time = 1_793_800_000_000L;
        c.app = "WhatsApp";
        c.conversation = "Aile; Grubu";
        c.sender = "Ayşe";
        c.text = "Akşam \"7\"de\ngeliyorum";
        c.deleted = false;
        String csv = Formats.csv(Arrays.asList(c), IST);
        assertTrue(csv.startsWith((char) 0xFEFF + "Tarih;Saat;"));
        assertTrue(csv, csv.contains(";WhatsApp;\"Aile; Grubu\";Ayşe;\"Akşam \"\"7\"\"de\ngeliyorum\";\r\n"));
    }

    @Test
    public void bodyPrefersExpandedText() {
        Capture c = new Capture();
        c.text = "Kısa";
        c.big = "Kısa metnin tamamı burada";
        assertEquals("Kısa metnin tamamı burada", c.body());
        c.big = null;
        assertEquals("Kısa", c.body());
    }
}
