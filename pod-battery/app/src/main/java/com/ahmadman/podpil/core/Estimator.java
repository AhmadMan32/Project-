package com.ahmadman.podpil.core;

/**
 * "How long is left": Apple's published listening times, refined by how fast this user's
 * AirPods actually drain. Every number here is an estimate.
 */
public final class Estimator {

    private Estimator() {
    }

    public static final class Estimate {
        /** Listening time left in the pods that are in use; -1 = unknown / not in use. */
        public int podMinutes = -1;
        /** The pods are in the case charging. */
        public boolean podsCharging;
        /** Until the charging pods are full; -1 = not charging. */
        public int podsFullMinutes = -1;
        /** Extra listening time stored in the case; -1 = no case / unknown. */
        public int caseMinutes = -1;
        /** How many full pod charges the case holds. */
        public double caseCharges = -1;
        /** Until the case is full while it is plugged in; -1 = not charging. */
        public int caseFullMinutes = -1;
        /** Pods + case. */
        public int totalMinutes = -1;
        /** Based on this user's own measured drain rate. */
        public boolean learned;
    }

    /** Minutes of listening per 1 % of pod battery, from Apple's numbers. */
    public static double ratedMpp(Models.Model m) {
        return m.podMinutes / 100.0;
    }

    /**
     * @param learnedMpp measured minutes per percent, 0 if not known yet
     * @param levelSince when the lowest pod level in use was first seen at its current value
     */
    public static Estimate estimate(Status s, double learnedMpp, long levelSince, long now) {
        Estimate e = new Estimate();
        Models.Model m = s.model();
        double mpp = learnedMpp > 0 ? learnedMpp : ratedMpp(m);
        e.learned = learnedMpp > 0;

        int use = inUseLevel(s);
        if (use != Status.UNKNOWN) {
            double minutes = use * mpp;
            double floor = Math.max(0, (use - (s.precise() ? 1 : 5)) * mpp);
            if (levelSince > 0 && now > levelSince) {
                minutes = Math.max(floor, minutes - (now - levelSince) / 60_000.0);
            }
            e.podMinutes = (int) Math.round(minutes);
        } else {
            int charging = Status.UNKNOWN;
            if (s.leftCharging && s.left != Status.UNKNOWN) {
                charging = s.left;
            }
            if (s.rightCharging && s.right != Status.UNKNOWN) {
                charging = charging == Status.UNKNOWN ? s.right : Math.min(charging, s.right);
            }
            if (charging != Status.UNKNOWN) {
                e.podsCharging = true;
                e.podsFullMinutes = podsFull(charging);
            }
        }
        if (m.hasCase() && s.caseLevel != Status.UNKNOWN) {
            double caseCapacity = m.totalMinutes - m.podMinutes;
            e.caseMinutes = (int) Math.round(s.caseLevel / 100.0 * caseCapacity * (mpp / ratedMpp(m)));
            e.caseCharges = s.caseLevel / 100.0 * caseCapacity / m.podMinutes;
            if (s.caseCharging) {
                e.caseFullMinutes = (int) Math.round((100 - s.caseLevel) * 1.5);
            }
        }
        if (e.podMinutes >= 0 || e.caseMinutes >= 0) {
            e.totalMinutes = Math.max(0, e.podMinutes) + Math.max(0, e.caseMinutes);
        }
        return e;
    }

    /** Lowest level among the pods out of the case (not charging); UNKNOWN if none. */
    public static int inUseLevel(Status s) {
        if (s.model().single) {
            return s.left != Status.UNKNOWN && !s.leftCharging ? s.left : Status.UNKNOWN;
        }
        if (s.bothInCase) {
            return Status.UNKNOWN;
        }
        int level = Status.UNKNOWN;
        if (s.left != Status.UNKNOWN && !s.leftCharging) {
            level = s.left;
        }
        if (s.right != Status.UNKNOWN && !s.rightCharging) {
            level = level == Status.UNKNOWN ? s.right : Math.min(level, s.right);
        }
        return level;
    }

    /** Pods charge fast up to 80 %, then slower. */
    static int podsFull(int level) {
        if (level >= 100) {
            return 0;
        }
        double m = 0;
        if (level < 80) {
            m += (80 - level) * 0.5;
        }
        m += 100 - Math.max(level, 80);
        return (int) Math.round(m);
    }

    /**
     * Measures the real drain rate: the time between two drops of the in-use level while the
     * pods are worn continuously.
     */
    public static final class Learner {
        private static final long GAP_MS = 5 * 60_000L;

        public double mpp;
        public int samples;
        private int lastLevel = Status.UNKNOWN;
        private long levelSince;
        private long lastSeen;
        private boolean anchored;

        public Learner(double mpp, int samples) {
            this.mpp = mpp;
            this.samples = samples;
        }

        /** Measured minutes per percent once there are enough samples, else 0. */
        public double usable() {
            return samples >= 3 ? mpp : 0;
        }

        /** When the current level was first seen (0 if unknown). */
        public long since(int level) {
            return level == lastLevel ? levelSince : 0;
        }

        /** @return true when a new sample was taken (worth saving) */
        public boolean observe(int level, double ratedMpp, long now) {
            if (level == Status.UNKNOWN) {
                lastLevel = Status.UNKNOWN;
                return false;
            }
            if (lastSeen != 0 && now - lastSeen > GAP_MS) {
                lastLevel = Status.UNKNOWN; // not watched continuously: start again
            }
            lastSeen = now;
            if (lastLevel == Status.UNKNOWN || level > lastLevel) {
                lastLevel = level;
                levelSince = now;
                anchored = false; // first seen in the middle of a level
                return false;
            }
            if (level == lastLevel) {
                return false;
            }
            boolean sampled = false;
            if (anchored) {
                double sample = (now - levelSince) / 60_000.0 / (lastLevel - level);
                sample = Math.max(ratedMpp * 0.25, Math.min(ratedMpp * 4, sample));
                mpp = samples == 0 ? sample : mpp * 0.7 + sample * 0.3;
                samples++;
                sampled = true;
            }
            lastLevel = level;
            levelSince = now;
            anchored = true;
            return sampled;
        }
    }
}
