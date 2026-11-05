package com.ahmadman.kenarpanel.atv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * This phone's identity for the TV: an ordinary RSA key with a self-signed certificate, like the
 * one Google's TV remote and androidtvremote2 use.
 *
 * <p>It is not kept in the Android keystore on purpose: TVs ask for RSA-PSS signatures in the
 * TLS handshake, and the TLS library then needs a raw RSA operation that keystore keys refuse
 * unless they were made with "no padding" allowed (Conscrypt issue 1201). The handshake then
 * failed before the TV said anything. A plain key signs every way TLS asks.
 */
public final class SelfSigned implements Tls.Identity {

    private static final String ALIAS = "phone";
    private static final byte[] OID_SHA256_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_COMMON_NAME = {0x55, 0x04, 0x03};
    private static final byte[] OID_BASIC_CONSTRAINTS = {0x55, 0x1D, 0x13};
    private static final byte[] OID_SUBJECT_ALT_NAME = {0x55, 0x1D, 0x11};

    private final PrivateKey key;
    private final X509Certificate cert;

    private SelfSigned(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    /** A new 2048-bit key and a certificate valid for 10 years. Takes a moment: not on the UI thread. */
    public static SelfSigned generate(String name, long now) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        KeyPair pair = g.generateKeyPair();

        byte[] sigAlg = seq(tlv(0x06, OID_SHA256_RSA), new byte[]{0x05, 0x00});
        byte[] dn = seq(tlv(0x31, seq(tlv(0x06, OID_COMMON_NAME), tlv(0x0C, name.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(time(now - 86_400_000L), time(now + 10L * 365 * 86_400_000L));
        byte[] basicConstraints = seq(tlv(0x06, OID_BASIC_CONSTRAINTS),
                tlv(0x04, seq(new byte[]{0x01, 0x01, (byte) 0xFF}, new byte[]{0x02, 0x01, 0x00})));
        byte[] altName = seq(tlv(0x06, OID_SUBJECT_ALT_NAME),
                tlv(0x04, seq(tlv(0x82, name.getBytes(StandardCharsets.US_ASCII)))));
        byte[] tbs = seq(
                tlv(0xA0, new byte[]{0x02, 0x01, 0x02}), // v3
                tlv(0x02, BigInteger.valueOf(now / 1000).toByteArray()),
                sigAlg,
                dn,
                validity,
                dn,
                pair.getPublic().getEncoded(), // SubjectPublicKeyInfo
                tlv(0xA3, seq(basicConstraints, altName)));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1]; // no unused bits
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bits));
        return new SelfSigned(pair.getPrivate(), parse(der));
    }

    /** Key and certificate, to store and {@link #decode} later. */
    public byte[] encode() throws IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeInt(1); // format
            byte[] k = key.getEncoded();
            byte[] c = cert.getEncoded();
            out.writeInt(k.length);
            out.write(k);
            out.writeInt(c.length);
            out.write(c);
            out.flush();
            return b.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    public static SelfSigned decode(byte[] data) throws IOException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 1) {
                throw new IOException("unknown format");
            }
            byte[] k = new byte[in.readInt()];
            in.readFully(k);
            byte[] c = new byte[in.readInt()];
            in.readFully(c);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
            return new SelfSigned(key, parse(c));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException(e);
        }
    }

    public X509Certificate certificate() {
        return cert;
    }

    @Override
    public RSAPublicKey publicKey() {
        return (RSAPublicKey) cert.getPublicKey();
    }

    @Override
    public KeyManager[] keyManagers() {
        return new KeyManager[]{new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return ALIAS;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }
        }};
    }

    private static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    // ---------------------------------------------------------------------------------------
    // DER

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int n = value.length;
        if (n < 0x80) {
            b.write(n);
        } else if (n < 0x100) {
            b.write(0x81);
            b.write(n);
        } else if (n < 0x10000) {
            b.write(0x82);
            b.write(n >> 8);
            b.write(n);
        } else {
            b.write(0x83);
            b.write(n >> 16);
            b.write(n >> 8);
            b.write(n);
        }
        b.write(value, 0, n);
        return b.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.write(p, 0, p.length);
        }
        return tlv(0x30, b.toByteArray());
    }

    /** UTCTime until 2049, GeneralizedTime after (RFC 5280). */
    static byte[] time(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(ms);
        int year = c.get(Calendar.YEAR);
        String rest = String.format(Locale.ROOT, "%02d%02d%02d%02d%02dZ", c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        if (year < 2050) {
            return tlv(0x17, (String.format(Locale.ROOT, "%02d", year % 100) + rest).getBytes(StandardCharsets.US_ASCII));
        }
        return tlv(0x18, (String.format(Locale.ROOT, "%04d", year) + rest).getBytes(StandardCharsets.US_ASCII));
    }
}
