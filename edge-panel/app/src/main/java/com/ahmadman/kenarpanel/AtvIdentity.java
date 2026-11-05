package com.ahmadman.kenarpanel;

import android.content.Context;

import com.ahmadman.kenarpanel.atv.SelfSigned;
import com.ahmadman.kenarpanel.atv.Tls;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * This phone's identity for the TV: made once, kept in the app's private storage (never backed
 * up or copied to another phone). See {@link SelfSigned} for why it is not a keystore key.
 */
final class AtvIdentity {

    private static final String FILE = "atv_identity";
    /** Version 1.2–1.5 kept a keystore key under this name; TLS could not use it with TVs. */
    private static final String OLD_KEYSTORE_ALIAS = "kenarpanel_atv";

    /** Tests put an identity here. */
    static Tls.Identity testIdentity;
    private static Tls.Identity cached;

    private AtvIdentity() {
    }

    /** Slow the first time (key generation): call from a background thread. */
    static synchronized Tls.Identity get(Context c) throws GeneralSecurityException, IOException {
        if (testIdentity != null) {
            return testIdentity;
        }
        if (cached != null) {
            return cached;
        }
        File f = new File(c.getApplicationContext().getNoBackupFilesDir(), FILE);
        if (f.isFile()) {
            try {
                cached = SelfSigned.decode(read(f));
                return cached;
            } catch (IOException e) {
                // unreadable: make a new one (the TV then asks to pair again)
            }
        }
        SelfSigned made = SelfSigned.generate("kenarpanel", System.currentTimeMillis());
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(made.encode());
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("cannot save the identity");
        }
        deleteOldKeystoreKey();
        cached = made;
        return cached;
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = in.read(b, n, b.length - n);
                if (r < 0) {
                    throw new IOException("short file");
                }
                n += r;
            }
            return b;
        }
    }

    private static void deleteOldKeystoreKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(OLD_KEYSTORE_ALIAS)) {
                ks.deleteEntry(OLD_KEYSTORE_ALIAS);
            }
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            // nothing to clean up
        }
    }
}
