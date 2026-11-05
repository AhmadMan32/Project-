package com.ahmadman.podpil.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class CoreTest {

    /** A proximity-pairing broadcast as the AirPods send it. */
    static byte[] packet(int model, int status, int pods, int flagsAndCase, int lid, byte[] enc) {
        byte[] d = new byte[27];
        d[0] = 0x07;
        d[1] = 0x19;
        d[2] = 0x01;
        d[3] = (byte) (model >> 8);
        d[4] = (byte) model;
        d[5] = (byte) status;
        d[6] = (byte) pods;
        d[7] = (byte) flagsAndCase;
        d[8] = (byte) lid;
        d[9] = 0x00;
        d[10] = 0x04;
        if (enc != null) {
            System.arraycopy(enc, 0, d, 11, 16);
        }
        return d;
    }

    @Test
    public void broadcastLevelsChargingAndEars() {
        Status s = Proximity.parse(packet(0x1420, 0x2A, 0x87, 0x46, 0x00, null));
        assertNotNull(s);
        assertEquals("AirPods Pro 2", s.model().name);
        assertEquals(70, s.left);
        assertEquals(80, s.right);
        assertEquals(60, s.caseLevel);
        assertTrue(s.caseCharging);
        assertFalse(s.leftCharging);
        assertTrue(s.leftInEar);
        assertTrue(s.rightInEar);
        assertFalse(s.precise());
        assertEquals(Status.Source.BROADCAST, s.source);
    }

    @Test
    public void broadcastFromTheRightPodIsSwapped() {
        // bit 5 clear: the right pod is broadcasting, nibbles and charging bits swap
        Status s = Proximity.parse(packet(0x1420, 0x02, 0x87, 0x1A, 0x00, null));
        assertEquals(80, s.left);
        assertEquals(70, s.right);
        assertTrue(s.rightCharging);
        assertFalse(s.leftCharging);
        assertEquals(100, s.caseLevel); // 10 = full
        assertTrue(s.rightInEar);
        assertFalse(s.leftInEar);
    }

    @Test
    public void unknownValuesLidAndOtherMessages() {
        Status s = Proximity.parse(packet(0x2420, 0x64, 0xF5, 0x5F, 0x08, null));
        assertEquals(50, s.left);
        assertEquals(Status.UNKNOWN, s.right);
        assertEquals(Status.UNKNOWN, s.caseLevel);
        assertTrue(s.bothInCase);
        assertEquals(Boolean.FALSE, s.lidOpen); // bit 3 set = closed
        assertEquals(Boolean.TRUE, Proximity.parse(packet(0x2420, 0x64, 0x55, 0x55, 0x00, null)).lidOpen);
        assertEquals(100, Proximity.parse(packet(0x2420, 0x20, 0x0C, 0x55, 0x00, null)).left); // > 10 capped

        assertNull(Proximity.parse(new byte[]{0x02, 0x15, 0x01}));
        byte[] other = packet(0x1420, 0x20, 0x55, 0x55, 0, null);
        other[0] = 0x10; // another continuity message
        assertNull(Proximity.parse(other));
        other = packet(0x1420, 0x20, 0x55, 0x55, 0, null);
        other[2] = 0x00; // not the pairing prefix: "ghost" frame
        assertNull(Proximity.parse(other));
    }

    @Test
    public void singleBatteryHeadphones() {
        Status s = Proximity.parse(packet(0x0A20, 0x20, 0x07, 0x1F, 0x00, null));
        assertTrue(s.model().single);
        assertEquals("AirPods Max", s.model().name);
        assertEquals(70, s.left);
        assertTrue(s.leftCharging);
        assertEquals(Status.UNKNOWN, s.right);
        assertFalse(s.model().hasCase());
        Models.Model unknown = Models.of(0x7720);
        assertFalse(unknown.known);
        assertEquals("AirPods", unknown.name);
    }

    static byte[] aes(byte[] key, byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(plain);
    }

    @Test
    public void encryptedPartGivesExactPercent() throws Exception {
        byte[] key = Crypto.hexKey("00112233 44556677 8899aabb ccddeeff");
        byte[] plain = new byte[16];
        plain[1] = 83;                     // left 83 %
        plain[2] = (byte) (0x80 | 79);     // right 79 %, charging
        plain[3] = 62;                     // case 62 %
        Status s = Proximity.parse(packet(0x1420, 0x2A, 0x88, 0x06, 0x00, aes(key, plain)));
        assertTrue(Crypto.decrypt(s, key));
        assertEquals(83, s.left);
        assertEquals(79, s.right);
        assertTrue(s.rightCharging);
        assertEquals(62, s.caseLevel);
        assertEquals(Status.Source.DECRYPTED, s.source);
        assertTrue(s.precise());

        // wrong key: garbage that does not match the public 10 % values is rejected
        Status t = Proximity.parse(packet(0x1420, 0x2A, 0x88, 0x06, 0x00, aes(key, plain)));
        byte[] wrong = Crypto.hexKey("ffeeddccbbaa99887766554433221100");
        int tries = 0;
        boolean accepted = Crypto.decrypt(t, wrong);
        if (accepted) {
            tries++; // only acceptable if it happens to look plausible; values must stay within a step
            assertTrue(Math.abs(t.left - 80) <= 10);
        } else {
            assertEquals(80, t.left);
            assertEquals(Status.Source.BROADCAST, t.source);
        }
        assertTrue(tries <= 1);

        // exact value far from the public one (another pair nearby) is rejected
        plain[1] = 20;
        Status u = Proximity.parse(packet(0x1420, 0x2A, 0x88, 0x06, 0x00, aes(key, plain)));
        assertFalse(Crypto.decrypt(u, key));
        assertEquals(80, u.left);
    }

    @Test
    public void identityKeyRecognisesOwnAddress() {
        // Bluetooth core specification sample: ah(IRK, 0x708194) = 0x0DFBAA
        byte[] specIrk = Crypto.hexKey("ec0234a357c8ad05341010a60a397d9b");
        byte[] irk = Crypto.reverse(specIrk); // stored little endian, as the AirPods send it
        assertTrue(Crypto.ownAddress("70:81:94:0D:FB:AA", irk));
        assertTrue(Crypto.ownAddress("AA:FB:0D:94:81:70", irk)); // reversed byte order
        assertFalse(Crypto.ownAddress("70:81:94:0D:FB:AB", irk));
        assertFalse(Crypto.ownAddress("not an address", irk));
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xFB, 0x0D},
                Crypto.ah(irk, new byte[]{(byte) 0x94, (byte) 0x81, 0x70}));
    }

    @Test
    public void keyTextIsForgiving() {
        assertNotNull(Crypto.hexKey("<00112233 44556677 8899aabb ccddeeff>"));
        assertNotNull(Crypto.hexKey("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"));
        assertNull(Crypto.hexKey("0011"));
        assertNull(Crypto.hexKey("zz112233445566778899aabbccddeeff"));
        assertEquals("00112233445566778899aabbccddeeff", Crypto.hex(Crypto.hexKey("0x00112233445566778899AABBCCDDEEFF")));
    }

    @Test
    public void accessoryProtocolBatteryAndKeys() {
        byte[] battery = Aap.bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
                0x02, 0x01, 87, 0x02, 0x01,    // right 87 %, discharging
                0x04, 0x01, 84, 0x02, 0x01,    // left 84 %
                0x08, 0x01, 51, 0x01, 0x01);   // case 51 %, charging
        assertEquals(Aap.Kind.BATTERY, Aap.kind(battery));
        Status s = new Status();
        s.modelId = 0x1420;
        assertTrue(Aap.battery(battery, s));
        assertEquals(84, s.left);
        assertEquals(87, s.right);
        assertEquals(51, s.caseLevel);
        assertTrue(s.caseCharging);
        assertFalse(s.leftCharging);
        assertEquals(Status.Source.AAP, s.source);

        byte[] gone = Aap.bytes(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01, 0x08, 0x01, 0xFF, 0x04, 0x01);
        assertTrue(Aap.battery(gone, s));
        assertEquals(Status.UNKNOWN, s.caseLevel); // 255 = case disconnected

        byte[] keys = new byte[7 + 4 + 16 + 4 + 16];
        byte[] head = Aap.bytes(0x04, 0x00, 0x04, 0x00, 0x31, 0x00, 0x02);
        System.arraycopy(head, 0, keys, 0, 7);
        keys[7] = 0x01;
        keys[9] = 16;
        for (int i = 0; i < 16; i++) {
            keys[11 + i] = (byte) i;
        }
        keys[27] = 0x04;
        keys[29] = 16;
        for (int i = 0; i < 16; i++) {
            keys[31 + i] = (byte) (0xA0 + i);
        }
        Aap.Keys k = Aap.keys(keys);
        assertNotNull(k);
        assertEquals("000102030405060708090a0b0c0d0e0f", Crypto.hex(k.irk));
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", Crypto.hex(k.enc));

        assertEquals(Aap.Kind.CONNECTED, Aap.kind(Aap.bytes(0x01, 0x00, 0x04, 0x00, 0x00, 0x00)));
        assertEquals(Aap.Kind.OTHER, Aap.kind(Aap.bytes(0x01, 0x00, 0x04, 0x00, 0x01, 0x00)));
        assertEquals(Aap.Kind.OTHER, Aap.kind(new byte[]{1}));
        assertEquals(16, Aap.HANDSHAKE.length);
    }

    @Test
    public void estimatesFromAppleTimesAndOwnUsage() {
        Status s = new Status();
        s.modelId = 0x1420; // 6 h per charge, 30 h with case
        s.left = 80;
        s.right = 70;
        s.caseLevel = 60;
        long now = 10_000_000L;
        Estimator.Estimate e = Estimator.estimate(s, 0, 0, now);
        assertEquals(252, e.podMinutes);          // 70 % of 6 h
        assertEquals(864, e.caseMinutes);         // 60 % of 24 h in the case
        assertEquals(2.4, e.caseCharges, 0.001);
        assertEquals(252 + 864, e.totalMinutes);
        assertFalse(e.learned);

        // 30 minutes after the level first showed 70 %: counts down, but not below 65 %
        e = Estimator.estimate(s, 0, now - 30 * 60_000L, now);
        assertEquals(234, e.podMinutes);
        e = Estimator.estimate(s, 0, now - 10 * 60_000L, now);
        assertEquals(242, e.podMinutes);

        // own measured rate
        e = Estimator.estimate(s, 3.0, 0, now);
        assertEquals(210, e.podMinutes);
        assertTrue(e.learned);

        // both pods charging in the case
        s.leftCharging = true;
        s.rightCharging = true;
        s.left = 50;
        s.right = 60;
        e = Estimator.estimate(s, 0, 0, now);
        assertEquals(-1, e.podMinutes);
        assertTrue(e.podsCharging);
        assertEquals(35, e.podsFullMinutes);
        assertEquals(0, Estimator.podsFull(100));

        // single battery, no case
        Status max = new Status();
        max.modelId = 0x0A20;
        max.left = 50;
        e = Estimator.estimate(max, 0, 0, now);
        assertEquals(600, e.podMinutes);
        assertEquals(-1, e.caseMinutes);
    }

    @Test
    public void learnerMeasuresRealDrain() {
        Estimator.Learner l = new Estimator.Learner(0, 0);
        double rated = 3.6;
        // real battery drains 1 % every 3 minutes; the AirPods report 10 % steps; seen every minute
        int sampled = 0;
        long minute = 60_000L;
        for (int t = 0; t <= 150; t++) {
            int actual = (int) Math.floor(95 - t / 3.0);
            if (l.observe(actual / 10 * 10, rated, t * minute)) {
                sampled++;
            }
        }
        assertEquals(4, sampled);           // drops at 16 (anchor), 46, 76, 106, 136 minutes
        assertEquals(3.0, l.mpp, 0.05);
        assertEquals(3.0, l.usable(), 0.05);
        assertEquals(136 * minute, l.since(40)); // 49.67 % first reads as 40 %
        assertEquals(0, l.since(70));

        // a gap (AirPods out of range for 10 minutes) restarts the measurement
        assertFalse(l.observe(40, rated, 160 * minute));
        assertFalse(l.observe(30, rated, 165 * minute)); // first drop after the gap only anchors
        assertEquals(4, l.samples);
        // charging (level goes up) restarts too
        assertFalse(l.observe(90, rated, 166 * minute));
        assertFalse(l.observe(80, rated, 167 * minute));
        assertFalse(l.observe(Status.UNKNOWN, rated, 168 * minute));
        assertEquals(4, l.samples);

        Estimator.Learner fresh = new Estimator.Learner(2.5, 2);
        assertEquals(0, fresh.usable(), 0.001); // two samples are not enough
    }
}
