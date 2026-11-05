package com.ahmadman.kenarpanel.atv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.security.Provider;
import java.security.Security;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Pairing and control with Conscrypt, the TLS library Android phones use, on both sides. */
public class ConscryptTest {

    @Before
    public void useConscrypt() throws Exception {
        Provider p = (Provider) Class.forName("org.conscrypt.OpenSSLProvider").getConstructor().newInstance();
        Security.insertProviderAt(p, 1);
        assertEquals("Conscrypt", javax.net.ssl.SSLContext.getInstance("TLS").getProvider().getName());
    }

    @After
    public void restore() {
        Security.removeProvider("KeystoreLike");
        Security.removeProvider("Conscrypt");
    }

    private static void pairAndPress(FakeTv tv, Tls.Identity phone) throws Exception {
        Pairing p = new Pairing(phone);
        p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Kenar Panel", 5000);
        String pin = p.finish(tv.shownCode);
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
        assertTrue(s.key(Messages.KEY_HOME));
        assertEquals(Integer.valueOf(3), tv.keys.poll(3, TimeUnit.SECONDS));
        s.close();
    }

    /**
     * Why versions 1.2–1.5 never got through: TVs (Android 9 boxes with TLS 1.2 too) ask for an
     * RSA-PSS signature, which a keystore key without "no padding" cannot make, so the handshake
     * failed before pairing began. The same key passes when only PKCS#1 is asked for, which
     * shows PSS is the cause. The new identity passes every time.
     */
    @Test
    public void oldKeystoreKeyFailsWhereTheNewIdentityPairs() throws Exception {
        Security.insertProviderAt(new KeystoreLike.KeystoreProvider(), 1);
        SelfSigned phone = SelfSigned.generate("kenarpanel", System.currentTimeMillis());
        for (String tls : new String[]{"TLSv1.2", "TLSv1.3"}) {
            try (FakeTv tv = new FakeTv(tls)) {
                Pairing old = new Pairing(KeystoreLike.identity());
                try {
                    old.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Kenar Panel", 5000);
                    fail(tls + ": the keystore key got through");
                } catch (IOException e) {
                    assertEquals(tls, Failure.Kind.TLS, Failure.of(e));
                } finally {
                    old.close();
                }
                assertNull(tv.shownCode);
                pairAndPress(tv, phone);
            }
        }
        // the JDK's TLS sets itself up only without Conscrypt in front; the phone side stays Conscrypt
        Provider conscrypt = Security.getProvider("Conscrypt");
        Security.removeProvider("Conscrypt");
        FakeTv pkcs1Only;
        try {
            pkcs1Only = new FakeTv(new String[]{"TLSv1.2"}, new String[]{"rsa_pkcs1_sha256"});
        } finally {
            Security.insertProviderAt(conscrypt, 1);
        }
        try (FakeTv tv = pkcs1Only) {
            pairAndPress(tv, KeystoreLike.identity());
        }
    }

    @Test
    public void pairAndSendKeys() throws Exception {
        try (FakeTv tv = new FakeTv()) {
            Tls.Identity phone = FakeTv.identity("phone");
            Pairing p = new Pairing(phone);
            p.start("127.0.0.1", tv.pairingServer.getLocalPort(), "Kenar Panel", 5000);
            assertNotNull(tv.shownCode);
            String pin = p.finish(tv.shownCode);
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
            assertTrue(s.key(Messages.KEY_DPAD_CENTER));
            assertEquals(Integer.valueOf(23), tv.keys.poll(3, TimeUnit.SECONDS));
            s.close();
        }
    }
}
