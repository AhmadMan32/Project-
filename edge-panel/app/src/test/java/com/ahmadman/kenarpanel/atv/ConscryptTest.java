package com.ahmadman.kenarpanel.atv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
        Security.removeProvider("Conscrypt");
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
