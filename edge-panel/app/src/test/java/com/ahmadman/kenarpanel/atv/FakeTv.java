package com.ahmadman.kenarpanel.atv;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * An Android TV for tests: real TLS with client certificates on two local ports, the pairing
 * handshake with a code "shown on screen", and the remote channel that records key presses.
 */
public final class FakeTv implements AutoCloseable {

    public final SSLServerSocket pairingServer;
    public final SSLServerSocket remoteServer;
    final RSAPublicKey tvKey;
    public volatile String shownCode;
    public volatile String pairedClient; // fingerprint of the paired phone certificate
    public final BlockingQueue<Integer> keys = new LinkedBlockingQueue<>();
    final BlockingQueue<Long> pongs = new LinkedBlockingQueue<>();
    volatile long clientFeatures = -1;
    private volatile boolean closed;

    static KeyStore store(String name) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = FakeTv.class.getResourceAsStream("/atv/" + name + ".p12")) {
            ks.load(in, "secret".toCharArray());
        }
        return ks;
    }

    /** A phone identity from a test key store. */
    public static Tls.Identity identity(String name) throws Exception {
        KeyStore ks = store(name);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "secret".toCharArray());
        RSAPublicKey pk = (RSAPublicKey) ks.getCertificate(name).getPublicKey();
        return new Tls.Identity() {
            @Override
            public javax.net.ssl.KeyManager[] keyManagers() {
                return kmf.getKeyManagers();
            }

            @Override
            public RSAPublicKey publicKey() {
                return pk;
            }
        };
    }

    /** Accepts any phone while pairing; on the remote port only the paired one. */
    private final class ClientTrust implements X509TrustManager {
        private final boolean remote;

        ClientTrust(boolean remote) {
            this.remote = remote;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (remote && !Tls.fingerprint(chain[0]).equals(pairedClient)) {
                throw new CertificateException("unknown phone");
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public FakeTv() throws Exception {
        KeyStore ks = store("tv");
        tvKey = (RSAPublicKey) ks.getCertificate("tv").getPublicKey();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "secret".toCharArray());
        pairingServer = server(kmf, false);
        remoteServer = server(kmf, true);
        thread(this::pairingLoop);
        thread(this::remoteLoop);
    }

    private SSLServerSocket server(KeyManagerFactory kmf, boolean remote) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{new ClientTrust(remote)}, null);
        SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0);
        s.setNeedClientAuth(true);
        return s;
    }

    private static void thread(Runnable r) {
        Thread t = new Thread(r, "fake-tv");
        t.setDaemon(true);
        t.start();
    }

    private static Proto.Writer ok() {
        return new Proto.Writer().varint(1, 2).varint(2, 200);
    }

    private void pairingLoop() {
        while (!closed) {
            try (SSLSocket s = (SSLSocket) pairingServer.accept()) {
                s.startHandshake();
                X509Certificate phone = (X509Certificate) s.getSession().getPeerCertificates()[0];
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                expect(in, 10);
                Proto.writeDelimited(out, ok().message(11, new Proto.Writer().string(1, "Salon TV")).toByteArray());
                expect(in, 20);
                Proto.writeDelimited(out, ok().message(20, new Proto.Writer().varint(3, 1)).toByteArray());
                expect(in, 30);
                // the code on the screen: checksum byte + 2 random bytes
                byte[] tail = new byte[2];
                new SecureRandom().nextBytes(tail);
                byte[] hash = hash((RSAPublicKey) phone.getPublicKey(), tail);
                shownCode = String.format(Locale.ROOT, "%02X%02X%02X", hash[0] & 0xFF, tail[0] & 0xFF, tail[1] & 0xFF);
                Proto.writeDelimited(out, ok().message(31, new Proto.Writer()).toByteArray());
                byte[] secretMsg = Proto.readDelimited(in);
                Proto.Field secret = Proto.find(Proto.parse(Proto.find(Proto.parse(secretMsg), 40).bytes), 1);
                if (Arrays.equals(secret.bytes, hash)) {
                    pairedClient = Tls.fingerprint(phone);
                    Proto.writeDelimited(out, ok().message(41, new Proto.Writer().bytes(1, hash)).toByteArray());
                } else {
                    Proto.writeDelimited(out, new Proto.Writer().varint(1, 2).varint(2, 402).toByteArray());
                }
                in.read(); // wait for the phone to hang up
            } catch (IOException | CertificateException | RuntimeException e) {
                // next client
            }
        }
    }

    private byte[] hash(RSAPublicKey phone, byte[] tail) {
        try {
            MessageDigest h = MessageDigest.getInstance("SHA-256");
            h.update(Messages.unsigned(phone.getModulus()));
            h.update(Messages.unsigned(phone.getPublicExponent()));
            h.update(Messages.unsigned(tvKey.getModulus()));
            h.update(Messages.unsigned(tvKey.getPublicExponent()));
            h.update(tail);
            return h.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void expect(InputStream in, int field) throws IOException {
        byte[] m = Proto.readDelimited(in);
        if (m == null || Proto.find(Proto.parse(m), field) == null) {
            throw new IOException("expected field " + field);
        }
    }

    private void remoteLoop() {
        while (!closed) {
            try (SSLSocket s = (SSLSocket) remoteServer.accept()) {
                s.startHandshake();
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                Proto.Writer info = new Proto.Writer().string(1, "BRAVIA").string(2, "Sony").varint(3, 1).string(4, "1")
                        .string(5, "com.google.android.tv.remote.service").string(6, "5.2");
                Proto.writeDelimited(out, new Proto.Writer().message(1,
                        new Proto.Writer().varint(1, 622).message(2, info)).toByteArray());
                byte[] cfg = Proto.readDelimited(in);
                clientFeatures = Proto.find(Proto.parse(Proto.find(Proto.parse(cfg), 1).bytes), 1).value;
                Proto.writeDelimited(out, new Proto.Writer().message(2, new Proto.Writer().varint(1, 622)).toByteArray());
                Proto.readDelimited(in); // phone's set_active
                Proto.writeDelimited(out, new Proto.Writer().message(40, new Proto.Writer().bool(1, true)).toByteArray());
                Proto.writeDelimited(out, new Proto.Writer().message(8,
                        new Proto.Writer().varint(1, 7).varint(2, 8)).toByteArray());
                while (!closed) {
                    byte[] m = Proto.readDelimited(in);
                    if (m == null) {
                        break;
                    }
                    java.util.List<Proto.Field> f = Proto.parse(m);
                    Proto.Field k = Proto.find(f, 10);
                    Proto.Field p = Proto.find(f, 9);
                    if (k != null) {
                        java.util.List<Proto.Field> kf = Proto.parse(k.bytes);
                        if (Proto.find(kf, 2).value == 3) {
                            keys.add((int) Proto.find(kf, 1).value);
                        }
                    } else if (p != null) {
                        pongs.add(Proto.find(Proto.parse(p.bytes), 1).value);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // next client
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        pairingServer.close();
        remoteServer.close();
    }
}
