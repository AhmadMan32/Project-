package com.ahmadman.kenarpanel.atv;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AtvTest {

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }

    @Test
    public void pairingMessagesAreByteExact() throws Exception {
        // OuterMessage{protocol_version:2 status:200 pairing_request{service_name:"atvremote" client_name:"K"}}
        assertEquals("080210c801520e0a0961747672656d6f74651201" + "4b", hex(Messages.pairingRequest("K")));
        // options{input_encodings{type:HEXADECIMAL symbol_length:6} preferred_role:INPUT}
        assertEquals("080210c801a201080a04080310061801", hex(Messages.pairingOptions()));
        // configuration{encoding{type:HEXADECIMAL symbol_length:6} client_role:INPUT}
        assertEquals("080210c801f201080a04080310061001", hex(Messages.pairingConfiguration()));
        byte[] s = new byte[32];
        assertTrue(hex(Messages.pairingSecret(s)).startsWith("080210c801c20222" + "0a20"));

        Proto.Writer ack = new Proto.Writer().varint(1, 2).varint(2, 200).message(31, new Proto.Writer());
        assertEquals(Messages.Pairing.CONFIGURATION_ACK, Messages.pairingKind(ack.toByteArray()));
        Proto.Writer bad = new Proto.Writer().varint(1, 2).varint(2, 402);
        assertEquals(Messages.Pairing.ERROR, Messages.pairingKind(bad.toByteArray()));
    }

    @Test
    public void pairingSecretMatchesTheReferenceImplementation() throws Exception {
        RSAPublicKey phone = (RSAPublicKey) FakeTv.store("phone").getCertificate("phone").getPublicKey();
        RSAPublicKey tv = (RSAPublicKey) FakeTv.store("tv").getCertificate("tv").getPublicKey();
        // computed with androidtvremote2's formula in Python for these two certificates
        byte[] secret = Messages.pairingSecret(phone, tv, "821a2b");
        assertNotNull(secret);
        assertEquals("82c45786371a66aaee4a3fab6ffa8d7b6aadb599afe0568444029ce7a7ac967f", hex(secret));
        assertNull(Messages.pairingSecret(phone, tv, "811A2B")); // checksum byte wrong: mistyped
        assertNull(Messages.pairingSecret(phone, tv, "12345"));
        assertNull(Messages.pairingSecret(phone, tv, "GGGGGG"));
        assertEquals("821A2B", Messages.normalizeCode(" 82 1a2b "));
    }

    @Test
    public void remoteMessages() throws Exception {
        // remote_key_inject{key_code:DPAD_UP direction:SHORT}
        assertEquals("520408131003", hex(Messages.key(Messages.KEY_DPAD_UP)));
        assertEquals("4a020807", hex(Messages.pong(7)));
        assertEquals("1203" + "08e304", hex(Messages.setActive(611)));
        byte[] cfg = Messages.configure(622, "Redmi", "Xiaomi");
        List<Proto.Field> f = Proto.parse(Proto.find(Proto.parse(cfg), 1).bytes);
        assertEquals(611 & 622, Proto.find(f, 1).value); // only features both sides have
        assertEquals("Redmi", Proto.find(Proto.parse(Proto.find(f, 2).bytes), 1).string());

        Messages.Incoming ping = Messages.remote(new Proto.Writer().message(8,
                new Proto.Writer().varint(1, 42).varint(2, 5)).toByteArray());
        assertEquals(Messages.Remote.PING, ping.kind);
        assertEquals(42, ping.value);
        assertEquals(Messages.Remote.START, Messages.remote(new Proto.Writer().message(40,
                new Proto.Writer().bool(1, true)).toByteArray()).kind);
        assertEquals(Messages.Remote.OTHER, Messages.remote(new Proto.Writer().message(50,
                new Proto.Writer().varint(7, 10)).toByteArray()).kind);
    }

    @Test
    public void framingHandlesLongMessages() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] big = new byte[300];
        big[299] = 9;
        Proto.writeDelimited(out, big);
        Proto.writeDelimited(out, new byte[]{1, 2});
        byte[] all = out.toByteArray();
        assertEquals((byte) 0xAC, all[0]); // 300 = varint AC 02
        assertEquals(0x02, all[1]);
        ByteArrayInputStream in = new ByteArrayInputStream(all);
        assertArrayEquals(big, Proto.readDelimited(in));
        assertArrayEquals(new byte[]{1, 2}, Proto.readDelimited(in));
        assertNull(Proto.readDelimited(in));
    }

    @Test
    public void touchpadStepsAndTaps() {
        Touchpad t = new Touchpad(40, 10);
        List<Touchpad.Step> steps = new ArrayList<>();
        t.down(100, 100, 0);
        t.move(130, 105, steps::add);
        assertTrue(steps.isEmpty());
        t.move(185, 110, steps::add); // 85 px right: two steps
        assertEquals(2, steps.size());
        assertEquals(Touchpad.Step.RIGHT, steps.get(0));
        t.move(185, 60, steps::add); // then up
        assertEquals(Touchpad.Step.UP, steps.get(2));
        assertFalse(t.up(185, 60, 300));

        t.down(50, 50, 1000);
        assertTrue(t.up(53, 52, 1150)); // tap = select
        t.down(50, 50, 2000);
        assertFalse(t.up(50, 50, 2800)); // too long for a tap
    }

    @Test
    public void pairThenControlOverRealTls() throws Exception {
        try (FakeTv tv = new FakeTv()) {
            Tls.Identity phone = FakeTv.identity("phone");
            Pairing p = new Pairing(phone);
            p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Kenar Panel", 5000);
            assertNotNull(tv.shownCode); // the TV shows its code now
            String pin = p.finish(tv.shownCode.toLowerCase());
            assertEquals(64, pin.length());
            assertNotNull(tv.pairedClient);

            CountDownLatch ready = new CountDownLatch(1);
            AtomicBoolean refused = new AtomicBoolean();
            Session s = new Session(phone, "127.0.0.1", tv.remoteServer.getLocalPort(), pin, "Redmi", "Xiaomi",
                    new Session.Listener() {
                        @Override
                        public void onReady(Session x) {
                            ready.countDown();
                        }

                        @Override
                        public void onClosed(Session x, boolean needsPairing) {
                            refused.set(needsPairing);
                        }
                    });
            assertFalse(s.key(Messages.KEY_HOME)); // not connected yet
            s.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertEquals(Long.valueOf(7), tv.pongs.poll(3, TimeUnit.SECONDS)); // answered the ping
            assertTrue(s.screenOn()); // remote_start came before the ping
            assertEquals(611 & 622, tv.clientFeatures);
            assertTrue(s.key(Messages.KEY_POWER));
            assertTrue(s.key(Messages.KEY_DPAD_RIGHT));
            assertTrue(s.key(Messages.KEY_VOLUME_MUTE));
            assertEquals(Integer.valueOf(26), tv.keys.poll(3, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(22), tv.keys.poll(3, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(164), tv.keys.poll(3, TimeUnit.SECONDS));
            s.close();
            assertFalse(refused.get());
        }
    }

    @Test
    public void wrongCodeAndUnknownPhone() throws Exception {
        try (FakeTv tv = new FakeTv()) {
            Pairing p = new Pairing(FakeTv.identity("phone"));
            p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Kenar Panel", 5000);
            String code = tv.shownCode;
            String wrong = code.substring(0, 2) + (code.charAt(2) == 'A' ? 'B' : 'A') + code.substring(3);
            try {
                p.finish(wrong);
                fail("a wrong code must not pair");
            } catch (Pairing.WrongCodeException expected) {
                // either rejected by the checksum or by the TV
            }
            p.close();
            assertNull(tv.pairedClient);

            // a phone the TV does not know is refused: it has to pair
            CountDownLatch closed = new CountDownLatch(1);
            AtomicBoolean needsPairing = new AtomicBoolean();
            Session s = new Session(FakeTv.identity("phone"), "127.0.0.1", tv.remoteServer.getLocalPort(), null,
                    "Redmi", "Xiaomi", new Session.Listener() {
                        @Override
                        public void onReady(Session x) {
                        }

                        @Override
                        public void onClosed(Session x, boolean np) {
                            needsPairing.set(np);
                            closed.countDown();
                        }
                    });
            s.start();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(needsPairing.get());
        }
    }

    @Test
    public void selfSignedIdentityIsAValidCertificateAndSurvivesStoring() throws Exception {
        long now = 1_780_000_000_000L; // 2026
        SelfSigned id = SelfSigned.generate("kenarpanel", now);
        java.security.cert.X509Certificate c = id.certificate();
        c.verify(c.getPublicKey()); // self-signed, SHA256withRSA
        c.checkValidity(new java.util.Date(now));
        c.checkValidity(new java.util.Date(now + 9L * 365 * 86_400_000L));
        assertEquals(3, c.getVersion());
        assertEquals("CN=kenarpanel", c.getSubjectX500Principal().getName());
        assertEquals(2048, id.publicKey().getModulus().bitLength());
        assertEquals(0, c.getBasicConstraints()); // CA, path length 0, like androidtvremote2's certificate
        SelfSigned back = SelfSigned.decode(id.encode());
        assertArrayEquals(c.getEncoded(), back.certificate().getEncoded());
        assertEquals(id.publicKey(), back.publicKey());
        // dates after 2049 are written as GeneralizedTime
        assertEquals(0x18, SelfSigned.time(2_600_000_000_000L)[0]);
        assertEquals(0x17, SelfSigned.time(now)[0]);
        try {
            SelfSigned.decode(new byte[]{0, 0, 0, 9});
            fail("garbage must not load");
        } catch (IOException expected) {
            // a new identity is made then
        }
    }

    @Test
    public void theAppsOwnIdentityPairsAndControls() throws Exception {
        try (FakeTv tv = new FakeTv("TLSv1.2")) { // Android 9 TVs such as the Mi Box 4 stop at TLS 1.2
            SelfSigned phone = SelfSigned.decode(SelfSigned.generate("kenarpanel", System.currentTimeMillis()).encode());
            Pairing p = new Pairing(phone);
            p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Redmi Note", 5000);
            String pin = p.finish(tv.shownCode);
            assertEquals(Tls.fingerprint(phone.certificate()), tv.pairedClient);
            CountDownLatch ready = new CountDownLatch(1);
            Session s = new Session(phone, "127.0.0.1", tv.remoteServer.getLocalPort(), pin, "Redmi", "Xiaomi",
                    new Session.Listener() {
                        @Override
                        public void onReady(Session x) {
                            ready.countDown();
                        }

                        @Override
                        public void onClosed(Session x, boolean needsPairing) {
                        }
                    });
            s.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(s.key(Messages.KEY_DPAD_LEFT));
            assertEquals(Integer.valueOf(21), tv.keys.poll(3, TimeUnit.SECONDS));
            s.close();
        }
    }

    @Test
    public void failuresAreToldApart() throws Exception {
        Tls.Identity phone = FakeTv.identity("phone");
        try (FakeTv tv = new FakeTv()) {
            // a Mi Box 4 whose "Android TV Remote Service" was never updated
            tv.mode = FakeTv.Mode.OLD_SERVICE;
            Pairing p = new Pairing(phone);
            try {
                p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "K", 3000);
                fail("an old remote service cannot pair");
            } catch (IOException e) {
                assertTrue(e instanceof Pairing.OldServiceException);
                assertEquals(Failure.Kind.OLD_SERVICE, Failure.of(e));
            } finally {
                p.close();
            }

            tv.mode = FakeTv.Mode.REFUSES;
            p = new Pairing(phone);
            try {
                p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "K", 3000);
                fail("refused");
            } catch (IOException e) {
                assertEquals(Failure.Kind.TV_SAID_NO, Failure.of(e));
                assertEquals("status 400", e.getMessage());
            } finally {
                p.close();
            }
        }

        int closedPort;
        try (ServerSocket free = new ServerSocket(0)) {
            closedPort = free.getLocalPort();
        }
        Pairing p = new Pairing(phone);
        try {
            p.start("127.0.0.1", closedPort, "K", 3000);
            fail("nothing listens there");
        } catch (IOException e) {
            assertEquals(Failure.Kind.CLOSED_PORT, Failure.of(e));
            assertTrue(Failure.detail(e).startsWith("ConnectException"));
        }
        assertEquals(Failure.Kind.UNREACHABLE, Failure.of(new SocketTimeoutException("connect timed out")));
        assertEquals(Failure.Kind.UNREACHABLE, Failure.of(new java.net.ConnectException("connect failed: EHOSTUNREACH")));
        assertEquals("SSLHandshakeException: Read error",
                Failure.detail(new Tls.HandshakeException(new javax.net.ssl.SSLHandshakeException("Read error"))));
    }
}
