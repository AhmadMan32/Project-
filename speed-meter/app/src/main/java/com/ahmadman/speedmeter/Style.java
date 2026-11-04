package com.ahmadman.speedmeter;

import android.content.Context;
import android.content.SharedPreferences;

/** Appearance and behaviour settings of the floating meter. */
final class Style {

    static final String PREFS = "style";

    static final String BOTH = "both";
    static final String DOWN = "down";
    static final String UP = "up";
    static final String TOTAL = "total";

    static final String BG_NONE = "none";
    static final String BG_DARK = "dark";
    static final String BG_LIGHT = "light";

    /** both / down / up / total */
    String content = BOTH;
    /** two lines instead of side by side */
    boolean column;
    /** kbps/Mbps instead of KB/s, MB/s */
    boolean bits = true;
    int sizeSp = 12;
    String background = BG_NONE;
    boolean bars = true;
    boolean darkText;
    /** 30..100 % */
    int opacity = 100;
    /** locked = touches go through to the app below; unlocked = drag to move */
    boolean locked = true;
    /** -1 = default position */
    int x = -1;
    int y = -1;
    boolean statusIcon = true;
    boolean startOnBoot;
    int intervalMs = 1000;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Style load(Context c) {
        SharedPreferences p = prefs(c);
        Style s = new Style();
        s.content = p.getString("content", s.content);
        s.column = p.getBoolean("column", s.column);
        s.bits = p.getBoolean("bits", s.bits);
        s.sizeSp = p.getInt("sizeSp", s.sizeSp);
        s.background = p.getString("background", s.background);
        s.bars = p.getBoolean("bars", s.bars);
        s.darkText = p.getBoolean("darkText", s.darkText);
        s.opacity = Math.max(30, Math.min(100, p.getInt("opacity", s.opacity)));
        s.locked = p.getBoolean("locked", s.locked);
        s.x = p.getInt("x", s.x);
        s.y = p.getInt("y", s.y);
        s.statusIcon = p.getBoolean("statusIcon", s.statusIcon);
        s.startOnBoot = p.getBoolean("startOnBoot", s.startOnBoot);
        s.intervalMs = p.getInt("intervalMs", s.intervalMs);
        return s;
    }

    void save(Context c) {
        prefs(c).edit()
                .putString("content", content)
                .putBoolean("column", column)
                .putBoolean("bits", bits)
                .putInt("sizeSp", sizeSp)
                .putString("background", background)
                .putBoolean("bars", bars)
                .putBoolean("darkText", darkText)
                .putInt("opacity", opacity)
                .putBoolean("locked", locked)
                .putInt("x", x)
                .putInt("y", y)
                .putBoolean("statusIcon", statusIcon)
                .putBoolean("startOnBoot", startOnBoot)
                .putInt("intervalMs", intervalMs)
                .apply();
    }

    boolean showDown() {
        return BOTH.equals(content) || DOWN.equals(content);
    }

    boolean showUp() {
        return BOTH.equals(content) || UP.equals(content);
    }

    boolean showTotal() {
        return TOTAL.equals(content);
    }
}
