package com.ahmadman.podpil.core;

/** Battery and wear state of one pair of headphones at one moment. */
public final class Status {

    public static final int UNKNOWN = -1;

    /** Where the numbers come from, best first. */
    public enum Source {
        /** The AirPods' own accessory channel (AAP): exact 1 % values. */
        AAP,
        /** The encrypted part of the Bluetooth broadcast, decrypted with the AirPods' key: 1 % values. */
        DECRYPTED,
        /** The public part of the Bluetooth broadcast: 10 % steps. */
        BROADCAST
    }

    public int modelId;
    /** 0..100 or {@link #UNKNOWN}. For single-battery headphones only {@link #left} is used. */
    public int left = UNKNOWN;
    public int right = UNKNOWN;
    public int caseLevel = UNKNOWN;
    public boolean leftCharging;
    public boolean rightCharging;
    public boolean caseCharging;
    public boolean leftInEar;
    public boolean rightInEar;
    public boolean bothInCase;
    /** null = unknown. */
    public Boolean lidOpen;
    public Source source = Source.BROADCAST;
    public int rssi;
    public String address;
    public long time;
    /** Last 16 bytes of the broadcast (encrypted part), null for AAP. */
    public byte[] encrypted;
    /** Battery nibbles are swapped (the right pod is the one broadcasting). */
    public boolean flipped;

    public Models.Model model() {
        return Models.of(modelId);
    }

    public boolean precise() {
        return source != Source.BROADCAST;
    }

    public boolean anyKnown() {
        return left != UNKNOWN || right != UNKNOWN || caseLevel != UNKNOWN;
    }

    public Status copy() {
        Status s = new Status();
        s.modelId = modelId;
        s.left = left;
        s.right = right;
        s.caseLevel = caseLevel;
        s.leftCharging = leftCharging;
        s.rightCharging = rightCharging;
        s.caseCharging = caseCharging;
        s.leftInEar = leftInEar;
        s.rightInEar = rightInEar;
        s.bothInCase = bothInCase;
        s.lidOpen = lidOpen;
        s.source = source;
        s.rssi = rssi;
        s.address = address;
        s.time = time;
        s.encrypted = encrypted;
        s.flipped = flipped;
        return s;
    }
}
