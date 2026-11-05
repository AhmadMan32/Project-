package com.ahmadman.kenarpanel.atv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.CertificateException;
import java.security.interfaces.RSAPublicKey;

import javax.net.ssl.SSLSocket;

/**
 * One-time pairing with an Android TV (port 6467). {@link #start} makes the TV show a
 * 6-character code; {@link #finish} sends the secret made from that code. Blocking: call
 * from a background thread.
 */
public final class Pairing implements Closeable {

    /** The code did not match (mistyped, or an old code). */
    public static final class WrongCodeException extends IOException {
        WrongCodeException() {
            super("wrong code");
        }
    }

    private final Tls.Identity identity;
    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private Tls.TvTrust trust;

    public Pairing(Tls.Identity identity) {
        this.identity = identity;
    }

    /** Connects and asks the TV to show its code. */
    public void start(String host, int port, String clientName, int timeoutMs) throws IOException {
        trust = new Tls.TvTrust(null);
        socket = Tls.connect(host, port, timeoutMs, identity, trust);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        exchange(Messages.pairingRequest(clientName), Messages.Pairing.REQUEST_ACK);
        exchange(Messages.pairingOptions(), Messages.Pairing.OPTIONS);
        exchange(Messages.pairingConfiguration(), Messages.Pairing.CONFIGURATION_ACK);
        // the TV shows the code now; the user may take a while to type it
        socket.setSoTimeout(0);
    }

    /**
     * @param code the 6 characters shown on the TV
     * @return the TV certificate's fingerprint, to accept only this TV later
     */
    public String finish(String code) throws IOException {
        RSAPublicKey server = (RSAPublicKey) trust.seen.getPublicKey();
        byte[] secret = Messages.pairingSecret(identity.publicKey(), server, code);
        if (secret == null) {
            throw new WrongCodeException(); // checked locally: the first 2 characters are a checksum
        }
        socket.setSoTimeout(10_000);
        Proto.writeDelimited(out, Messages.pairingSecret(secret));
        byte[] reply = Proto.readDelimited(in);
        if (reply == null || Messages.pairingKind(reply) != Messages.Pairing.SECRET_ACK) {
            throw new WrongCodeException();
        }
        try {
            return Tls.fingerprint(trust.seen);
        } catch (CertificateException e) {
            throw new IOException(e);
        } finally {
            close();
        }
    }

    private void exchange(byte[] send, Messages.Pairing expect) throws IOException {
        Proto.writeDelimited(out, send);
        byte[] reply = Proto.readDelimited(in);
        if (reply == null) {
            throw new IOException("the TV closed the connection");
        }
        Messages.Pairing kind = Messages.pairingKind(reply);
        if (kind != expect) {
            throw new IOException("unexpected answer " + kind + " instead of " + expect);
        }
    }

    @Override
    public void close() {
        SSLSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }
}
