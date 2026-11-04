package com.ahmadman.notifsaver.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Times ("14:03:27"), day headers ("Bugün", "Dün", "12 Kasım 2026") and CSV export. */
public final class Formats {

    private Formats() {
    }

    public static String time(long ms, TimeZone tz) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
    }

    /** Short label for lists: time today, "Dün" yesterday, date otherwise. */
    public static String shortWhen(long ms, long now, TimeZone tz, Locale locale, String yesterday) {
        int diff = dayDiff(ms, now, tz);
        if (diff == 0) {
            SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
            f.setTimeZone(tz);
            return f.format(new Date(ms));
        }
        if (diff == 1) {
            return yesterday;
        }
        SimpleDateFormat f = new SimpleDateFormat(diff < 300 ? "d MMM" : "d MMM yyyy", locale);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
    }

    public static String dayHeader(long ms, long now, TimeZone tz, Locale locale, String today, String yesterday) {
        int diff = dayDiff(ms, now, tz);
        if (diff == 0) {
            return today;
        }
        if (diff == 1) {
            return yesterday;
        }
        SimpleDateFormat f = new SimpleDateFormat("d MMMM yyyy, EEEE", locale);
        f.setTimeZone(tz);
        return f.format(new Date(ms));
    }

    /** Calendar days between ms and now (0 = same day). */
    public static int dayDiff(long ms, long now, TimeZone tz) {
        return (int) (dayNumber(now, tz) - dayNumber(ms, tz));
    }

    static long dayNumber(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return Math.round(c.getTimeInMillis() / 86_400_000.0);
    }

    public static boolean sameDay(long a, long b, TimeZone tz) {
        return dayNumber(a, tz) == dayNumber(b, tz);
    }

    public static String csvHeader() {
        return (char) 0xFEFF + "Tarih;Saat;Uygulama;Sohbet / Başlık;Gönderen;Metin;Durum\r\n";
    }

    public static String csvRow(Capture c, TimeZone tz) {
        SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        d.setTimeZone(tz);
        return cell(d.format(new Date(c.time))) + ";" + cell(time(c.time, tz)) + ";" + cell(c.app) + ";"
                + cell(c.conversation != null ? c.conversation : c.title) + ";" + cell(c.sender) + ";"
                + cell(c.body()) + ";" + (c.recovered ? "silinen mesaj" : c.deleted ? "silindi bildirimi" : "") + "\r\n";
    }

    static String cell(String v) {
        if (v == null) {
            return "";
        }
        boolean quote = v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    public static String csv(List<Capture> rows, TimeZone tz) {
        StringBuilder sb = new StringBuilder(csvHeader());
        for (Capture c : rows) {
            sb.append(csvRow(c, tz));
        }
        return sb.toString();
    }
}
