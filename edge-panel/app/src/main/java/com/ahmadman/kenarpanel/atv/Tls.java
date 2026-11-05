package com.ahmadman.kenarpanel.atv;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** TLS with a client certificate to the TV, which uses its own self-signed certificate. */
public final class Tls {

    private Tls() {
    }

    /** This phone's key and certificate (the TV remembers it after pairing). */
    public interface Identity {
        KeyManager[] keyManagers();

        RSAPublicKey publicKey();
    }

    /**
     * Trusts the TV's certificate on first use (pairing) and afterwards only that same
     * certificate (its SHA-256 fingerprint is stored when pairing succeeds).
     */
    static final class TvTrust implements X509TrustManager {
        private final String pin;
        volatile X509Certificate seen;

        /** @param pin fingerprint to require, or null while pairing */
        TvTrust(String pin) {
            this.pin = pin;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("no TV certificate");
            }
            if (pin != null && !pin.equals(fingerprint(chain[0]))) {
                throw new CertificateException("the TV's certificate changed: pair again");
            }
            seen = chain[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client side only");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** The TV was reachable but the TLS handshake failed (it refused this phone, or a pin mismatch). */
    public static final class HandshakeException extends IOException {
        HandshakeException(Throwable cause) {
            super(cause);
        }
    }

    static SSLSocket connect(String host, int port, int timeoutMs, Identity id, TvTrust trust) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            raw.setTcpNoDelay(true);
        } catch (IOException e) {
            raw.close();
            throw e; // not reachable
        }
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(id.keyManagers(), new TrustManager[]{trust}, null);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setUseClientMode(true);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            raw.close();
            throw new IOException(e);
        } catch (IOException e) {
            raw.close();
            // a timeout is the network; an alert or a reset during the handshake is the TV saying no
            throw e instanceof java.net.SocketTimeoutException ? e : new HandshakeException(e);
        }
    }

    public static String fingerprint(X509Certificate cert) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }
}
