package com.ahmadman.disableheadphone;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ADB client identity of this app: an RSA key and a self-signed certificate, created once and kept
 * in the app's private storage. The phone remembers it after pairing ("Paired devices" list in
 * Wireless debugging), so later connections need no code.
 */
final class AdbManager extends AbsAdbConnectionManager {

    private static final String KEY_FILE = "adb_key.pk8";
    private static final String CERT_FILE = "adb_cert.der";

    private static AdbManager sInstance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbManager get(Context c) throws Exception {
        if (sInstance == null) {
            sInstance = new AdbManager(c.getApplicationContext());
        }
        return sInstance;
    }

    private AdbManager(Context c) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(c.getFilesDir(), KEY_FILE);
        File certFile = new File(c.getFilesDir(), CERT_FILE);
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
                try (InputStream in = new FileInputStream(certFile)) {
                    cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
                }
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            byte[] der = selfSignedCertificate(pair);
            key = pair.getPrivate();
            cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new java.io.ByteArrayInputStream(der));
            writeAll(keyFile, key.getEncoded());
            writeAll(certFile, der);
        }
        privateKey = key;
        certificate = cert;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "KulaklikKapat";
    }

    // ---------------------------------------------------------------------------------------
    // Files
    // ---------------------------------------------------------------------------------------

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Minimal self-signed X.509 v1 certificate (DER). adbd only uses the public key in it.
    // ---------------------------------------------------------------------------------------

    private static final byte[] OID_SHA256_RSA = {
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b};
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    private static byte[] selfSignedCertificate(KeyPair pair) throws Exception {
        byte[] algorithm = seq(OID_SHA256_RSA, new byte[]{0x05, 0x00});
        byte[] name = seq(tlv(0x31, seq(OID_CN,
                tlv(0x0c, "KulaklikKapat".getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(new Date(now - 24L * 3600 * 1000)),
                utcTime(new Date(now + 20L * 365 * 24 * 3600 * 1000)));
        byte[] serial = tlv(0x02, BigInteger.valueOf(now).toByteArray());
        byte[] tbs = seq(serial, algorithm, name, validity, name, pair.getPublic().getEncoded());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] sig = signer.sign();
        byte[] bitString = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(tbs, algorithm, tlv(0x03, bitString));
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p, 0, p.length);
        }
        return tlv(0x30, bos.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(tag);
        int len = value.length;
        if (len < 0x80) {
            bos.write(len);
        } else if (len < 0x100) {
            bos.write(0x81);
            bos.write(len);
        } else if (len < 0x10000) {
            bos.write(0x82);
            bos.write(len >> 8);
            bos.write(len & 0xff);
        } else {
            bos.write(0x83);
            bos.write(len >> 16);
            bos.write((len >> 8) & 0xff);
            bos.write(len & 0xff);
        }
        bos.write(value, 0, value.length);
        return bos.toByteArray();
    }
}
