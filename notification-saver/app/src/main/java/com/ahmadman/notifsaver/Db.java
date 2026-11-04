package com.ahmadman.notifsaver;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.ahmadman.notifsaver.core.Capture;
import com.ahmadman.notifsaver.core.Rules;

import java.util.ArrayList;
import java.util.List;

/** Local SQLite archive of every saved notification. Nothing leaves the phone. */
final class Db extends SQLiteOpenHelper {

    private static final String NAME = "notifications.db";
    private static final int VERSION = 1;

    /** Overview row: one app or one conversation with its latest entry. */
    static final class Group {
        String pkg;
        String app;
        String conversation;
        int count;
        Capture last;
    }

    Db(Context c) {
        super(c.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE n (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, app TEXT, title TEXT, "
                + "text TEXT, big TEXT, sub TEXT, conv TEXT, sender TEXT, time INTEGER NOT NULL, msg INTEGER NOT NULL DEFAULT 0, "
                + "deleted INTEGER NOT NULL DEFAULT 0, nkey TEXT, hash TEXT NOT NULL, search TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX n_time ON n(time)");
        db.execSQL("CREATE INDEX n_pkg_conv ON n(pkg, conv, time)");
        db.execSQL("CREATE INDEX n_hash ON n(hash)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // first version
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging(); // UI reads while the listener writes
    }

    /**
     * Inserts unless it is a duplicate: chat messages are unique by content and time; normal
     * notifications re-posted with the same content within {@link Rules#DUPLICATE_WINDOW_MS}.
     *
     * @return the new row id, or -1 when skipped as duplicate
     */
    long insert(Capture c) {
        SQLiteDatabase db = getWritableDatabase();
        String window = c.message ? "0" : String.valueOf(c.time - Rules.DUPLICATE_WINDOW_MS);
        try (Cursor cur = db.rawQuery("SELECT 1 FROM n WHERE hash = ? AND time >= ? LIMIT 1",
                new String[]{c.hash, window})) {
            if (cur.moveToFirst()) {
                return -1;
            }
        }
        ContentValues v = new ContentValues();
        v.put("pkg", c.pkg);
        v.put("app", c.app);
        v.put("title", c.title);
        v.put("text", c.text);
        v.put("big", c.big);
        v.put("sub", c.sub);
        v.put("conv", c.conversation);
        v.put("sender", c.sender);
        v.put("time", c.time);
        v.put("msg", c.message ? 1 : 0);
        v.put("deleted", c.deleted ? 1 : c.recovered ? 2 : 0);
        v.put("nkey", c.key);
        v.put("hash", c.hash);
        v.put("search", Rules.fold(join(c.app, c.title, c.conversation, c.sender, c.text, c.big, c.sub)));
        if (c.message && c.sender != null) {
            String[] same = {c.pkg, c.conversation == null ? "" : c.conversation, c.sender, String.valueOf(c.time)};
            String where = "pkg = ? AND IFNULL(conv, '') = ? AND sender = ? AND time = ? AND msg = 1 AND ";
            if (c.deleted) {
                // the sender deleted it: keep the saved original and mark it
                ContentValues r = new ContentValues();
                r.put("deleted", 2);
                db.update("n", r, where + "deleted = 0", same);
            } else {
                try (Cursor cur = db.rawQuery("SELECT 1 FROM n WHERE " + where + "deleted = 1 LIMIT 1", same)) {
                    if (cur.moveToFirst()) {
                        c.recovered = true; // marker arrived first (import of the notification shade)
                        v.put("deleted", 2);
                    }
                }
            }
        }
        c.id = db.insert("n", null, v);
        return c.id;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null) {
                sb.append(p).append('\n');
            }
        }
        return sb.toString();
    }

    private static final String COLS = "id, pkg, app, title, text, big, sub, conv, sender, time, msg, deleted, nkey, hash";

    private static Capture read(Cursor c) {
        Capture x = new Capture();
        x.id = c.getLong(0);
        x.pkg = c.getString(1);
        x.app = c.getString(2);
        x.title = c.getString(3);
        x.text = c.getString(4);
        x.big = c.getString(5);
        x.sub = c.getString(6);
        x.conversation = c.getString(7);
        x.sender = c.getString(8);
        x.time = c.getLong(9);
        x.message = c.getInt(10) != 0;
        int deleted = c.getInt(11);
        x.deleted = deleted == 1;
        x.recovered = deleted == 2;
        x.key = c.getString(12);
        x.hash = c.getString(13);
        return x;
    }

    private List<Capture> list(String sql, String... args) {
        List<Capture> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                out.add(read(c));
            }
        }
        return out;
    }

    /** Apps with their newest entry, newest first. */
    List<Group> apps() {
        List<Group> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS.replace("id,", "n.id,")
                + ", g.cnt FROM n JOIN (SELECT pkg AS gp, MAX(id) AS mid, COUNT(*) AS cnt FROM n GROUP BY pkg) g "
                + "ON n.id = g.mid ORDER BY n.time DESC", null)) {
            while (c.moveToNext()) {
                Group g = new Group();
                g.last = read(c);
                g.pkg = g.last.pkg;
                g.app = g.last.app;
                g.count = c.getInt(14);
                out.add(g);
            }
        }
        return out;
    }

    /** Conversations / titles of one app, newest first. */
    List<Group> conversations(String pkg) {
        List<Group> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + COLS.replace("id,", "n.id,")
                + ", g.cnt FROM n JOIN (SELECT IFNULL(conv, '') AS gc, MAX(id) AS mid, COUNT(*) AS cnt FROM n "
                + "WHERE pkg = ? GROUP BY IFNULL(conv, '')) g ON n.id = g.mid ORDER BY n.time DESC", new String[]{pkg})) {
            while (c.moveToNext()) {
                Group g = new Group();
                g.last = read(c);
                g.pkg = pkg;
                g.app = g.last.app;
                g.conversation = g.last.conversation;
                g.count = c.getInt(14);
                out.add(g);
            }
        }
        return out;
    }

    /** One conversation, oldest first, the newest {@code limit} entries. */
    List<Capture> thread(String pkg, String conv, int limit) {
        List<Capture> rows = list("SELECT " + COLS + " FROM n WHERE pkg = ? AND IFNULL(conv, '') = ? "
                + "ORDER BY time DESC, id DESC LIMIT " + limit, pkg, conv == null ? "" : conv);
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** Everything (or one app), newest first. */
    List<Capture> timeline(String pkg, int limit, int offset) {
        if (pkg != null) {
            return list("SELECT " + COLS + " FROM n WHERE pkg = ? ORDER BY time DESC, id DESC LIMIT " + limit
                    + " OFFSET " + offset, pkg);
        }
        return list("SELECT " + COLS + " FROM n ORDER BY time DESC, id DESC LIMIT " + limit + " OFFSET " + offset);
    }

    /** Folded full-text search (Turkish letters and case ignored), newest first. */
    List<Capture> search(String query, int limit, int offset) {
        return list("SELECT " + COLS + " FROM n WHERE search LIKE ? ESCAPE '\\' ORDER BY time DESC, id DESC LIMIT "
                + limit + " OFFSET " + offset, Rules.likePattern(query));
    }

    /**
     * Saved originals of deleted chat messages, plus "this message was deleted" markers whose
     * original was not seen (e.g. it arrived before the app was installed), newest first.
     */
    List<Capture> deletedMessages(int limit) {
        return list("SELECT " + COLS + " FROM n WHERE deleted = 2 OR (deleted = 1 AND NOT EXISTS (SELECT 1 FROM n o "
                + "WHERE o.deleted = 2 AND o.pkg = n.pkg AND IFNULL(o.conv, '') = IFNULL(n.conv, '') "
                + "AND o.sender = n.sender AND o.time = n.time)) ORDER BY time DESC, id DESC LIMIT " + limit);
    }

    int deletedCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM n WHERE deleted = 2 OR (deleted = 1 "
                + "AND NOT EXISTS (SELECT 1 FROM n o WHERE o.deleted = 2 AND o.pkg = n.pkg AND IFNULL(o.conv, '') = "
                + "IFNULL(n.conv, '') AND o.sender = n.sender AND o.time = n.time))", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    Capture get(long id) {
        List<Capture> l = list("SELECT " + COLS + " FROM n WHERE id = ?", String.valueOf(id));
        return l.isEmpty() ? null : l.get(0);
    }

    /** Package -> {app name, number of saved entries} for everything in the archive. */
    java.util.Map<String, String[]> packages() {
        java.util.Map<String, String[]> out = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT pkg, MAX(app), COUNT(*) FROM n GROUP BY pkg", null)) {
            while (c.moveToNext()) {
                out.put(c.getString(0), new String[]{c.getString(1), String.valueOf(c.getInt(2))});
            }
        }
        return out;
    }

    int countApp(String pkg) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM n WHERE pkg = ?", new String[]{pkg})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM n", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    long lastTime() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT MAX(time) FROM n", null)) {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        }
    }

    int countSince(long ms) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM n WHERE time >= ?",
                new String[]{String.valueOf(ms)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    int deleteOlderThan(long ms) {
        return getWritableDatabase().delete("n", "time < ?", new String[]{String.valueOf(ms)});
    }

    int deleteApp(String pkg) {
        return getWritableDatabase().delete("n", "pkg = ?", new String[]{pkg});
    }

    int deleteConversation(String pkg, String conv) {
        return getWritableDatabase().delete("n", "pkg = ? AND IFNULL(conv, '') = ?", new String[]{pkg, conv == null ? "" : conv});
    }

    int deleteOne(long id) {
        return getWritableDatabase().delete("n", "id = ?", new String[]{String.valueOf(id)});
    }

    void deleteAll() {
        getWritableDatabase().delete("n", null, null);
    }

    interface RowConsumer {
        void accept(Capture c);
    }

    /** Streams rows for export (oldest first) in pages to keep memory low. */
    void forEach(RowConsumer consumer) {
        long last = 0;
        while (true) {
            List<Capture> page = list("SELECT " + COLS + " FROM n WHERE id > ? ORDER BY id LIMIT 1000", String.valueOf(last));
            if (page.isEmpty()) {
                return;
            }
            for (Capture c : page) {
                consumer.accept(c);
                last = c.id;
            }
        }
    }
}
