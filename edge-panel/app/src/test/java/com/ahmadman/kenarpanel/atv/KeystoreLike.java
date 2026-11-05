package com.ahmadman.kenarpanel.atv;

import java.math.BigInteger;
import java.net.Socket;
import java.security.AlgorithmParameters;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Imitates the identity of Kenar Panel 1.2–1.5: an Android keystore RSA key made with signature
 * paddings PKCS#1 and PSS but no "no padding". Such a key never leaves the keystore
 * ({@code getEncoded()} is null), so Conscrypt asks the keystore for the raw RSA operation:
 * "RSA/ECB/PKCS1Padding" works, "RSA/ECB/NoPadding" (needed for RSA-PSS) is refused.
 */
final class KeystoreLike {

    private KeystoreLike() {
    }

    static final class HardwareKey implements PrivateKey, RSAKey {
        final RSAPrivateKey real;

        HardwareKey(RSAPrivateKey real) {
            this.real = real;
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return null;
        }

        @Override
        public byte[] getEncoded() {
            return null; // not extractable
        }

        @Override
        public BigInteger getModulus() {
            return real.getModulus();
        }
    }

    /** The keystore's cipher: PKCS#1 only, like a key without ENCRYPTION_PADDING_NONE. */
    public static final class Pkcs1OnlyCipher extends CipherSpi {
        private Cipher inner;

        @Override
        protected void engineSetMode(String mode) {
        }

        @Override
        protected void engineSetPadding(String padding) throws NoSuchPaddingException {
            if (!"PKCS1Padding".equalsIgnoreCase(padding)) {
                throw new NoSuchPaddingException(padding);
            }
        }

        @Override
        protected int engineGetBlockSize() {
            return 0;
        }

        @Override
        protected int engineGetOutputSize(int inputLen) {
            return 512;
        }

        @Override
        protected byte[] engineGetIV() {
            return null;
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
            if (!(key instanceof HardwareKey)) {
                throw new InvalidKeyException("not a keystore key");
            }
            try {
                inner = Cipher.getInstance("RSA/ECB/PKCS1Padding", "SunJCE");
                inner.init(opmode, ((HardwareKey) key).real);
            } catch (java.security.GeneralSecurityException e) {
                throw new InvalidKeyException(e);
            }
        }

        @Override
        protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidKeyException {
            engineInit(opmode, key, random);
        }

        @Override
        protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
                throws InvalidKeyException {
            engineInit(opmode, key, random);
        }

        @Override
        protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
            return inner.update(input, inputOffset, inputLen);
        }

        @Override
        protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
                throws javax.crypto.ShortBufferException {
            return inner.update(input, inputOffset, inputLen, output, outputOffset);
        }

        @Override
        protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
                throws IllegalBlockSizeException, BadPaddingException {
            return inner.doFinal(input, inputOffset, inputLen);
        }

        @Override
        protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset)
                throws IllegalBlockSizeException, BadPaddingException, javax.crypto.ShortBufferException {
            return inner.doFinal(input, inputOffset, inputLen, output, outputOffset);
        }
    }

    static final class KeystoreProvider extends Provider {
        @SuppressWarnings("deprecation")
        KeystoreProvider() {
            super("KeystoreLike", 1.0, "imitates AndroidKeyStore RSA keys");
            put("Cipher.RSA/ECB/PKCS1Padding", Pkcs1OnlyCipher.class.getName());
        }
    }

    /** The old identity, built on the "phone" test certificate. */
    static Tls.Identity identity() throws Exception {
        KeyStore ks = FakeTv.store("phone");
        X509Certificate cert = (X509Certificate) ks.getCertificate("phone");
        PrivateKey key = new HardwareKey((RSAPrivateKey) ks.getKey("phone", "secret".toCharArray()));
        return new Tls.Identity() {
            @Override
            public KeyManager[] keyManagers() {
                return new KeyManager[]{new X509ExtendedKeyManager() {
                    @Override
                    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                        return "phone";
                    }

                    @Override
                    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                        return "phone";
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
                        return new String[]{"phone"};
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
        };
    }
}
