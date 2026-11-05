package com.ahmadman.kenarpanel;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.ahmadman.kenarpanel.atv.Tls;

import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * This phone's identity for the TV: an RSA key made once in the Android keystore (it never
 * leaves the phone's secure hardware) with a self-signed certificate the TV remembers.
 */
final class AtvIdentity implements Tls.Identity {

    static final String ALIAS = "kenarpanel_atv";

    /** Tests put a key store identity here. */
    static Tls.Identity testIdentity;
    private static AtvIdentity cached;

    private final PrivateKey key;
    private final X509Certificate cert;

    private AtvIdentity(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }

    /** Slow the first time (key generation): call from a background thread. */
    static synchronized Tls.Identity get() throws GeneralSecurityException, IOException {
        if (testIdentity != null) {
            return testIdentity;
        }
        if (cached != null) {
            return cached;
        }
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            generate();
        }
        cached = new AtvIdentity((PrivateKey) ks.getKey(ALIAS, null), (X509Certificate) ks.getCertificate(ALIAS));
        return cached;
    }

    private static void generate() throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(new X500Principal("CN=kenarpanel"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 86_400_000L))
                .setCertificateNotAfter(new Date(now + 30L * 365 * 86_400_000L))
                .build();
        KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        g.initialize(spec);
        g.generateKeyPair();
    }

    /** Forgets the key (a new pairing then makes a new one). */
    static synchronized void delete() {
        cached = null;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (GeneralSecurityException | IOException ignored) {
            // nothing to delete
        }
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

    @Override
    public RSAPublicKey publicKey() {
        return (RSAPublicKey) cert.getPublicKey();
    }
}
