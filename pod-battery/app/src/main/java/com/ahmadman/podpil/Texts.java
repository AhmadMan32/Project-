package com.ahmadman.podpil;

import android.content.Context;

import com.ahmadman.podpil.core.Estimator;
import com.ahmadman.podpil.core.Status;

/** Percentages, durations and one-line summaries in the user's language. */
final class Texts {

    private Texts() {
    }

    static String pct(Context c, int level) {
        return level == Status.UNKNOWN ? "—" : c.getString(R.string.percent, level);
    }

    static String duration(Context c, int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) {
            return c.getString(R.string.dur_m, m);
        }
        if (m == 0) {
            return c.getString(R.string.dur_h, h);
        }
        return c.getString(R.string.dur_hm, h, m);
    }

    private static String level(Context c, int level, boolean charging) {
        return pct(c, level) + (charging && level != Status.UNKNOWN ? " ⚡" : "");
    }

    static String levels(Context c, Status s) {
        if (s.model().single) {
            return c.getString(R.string.levels_single, level(c, s.left, s.leftCharging));
        }
        return c.getString(R.string.levels_dual, level(c, s.left, s.leftCharging), level(c, s.right, s.rightCharging),
                level(c, s.caseLevel, s.caseCharging));
    }

    static String estimateLine(Context c, Estimator.Estimate e) {
        if (e == null) {
            return "";
        }
        if (e.podMinutes >= 0) {
            String s = c.getString(R.string.est_left, duration(c, e.podMinutes));
            if (e.caseMinutes > 0) {
                s += " · " + c.getString(R.string.est_total, duration(c, e.totalMinutes));
            }
            return s;
        }
        if (e.podsCharging) {
            return e.podsFullMinutes > 0 ? c.getString(R.string.est_charging, duration(c, e.podsFullMinutes))
                    : c.getString(R.string.est_full);
        }
        if (e.caseMinutes >= 0) {
            return c.getString(R.string.est_case_only, duration(c, e.caseMinutes));
        }
        return "";
    }
}
