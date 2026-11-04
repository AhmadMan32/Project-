package com.ahmadman.notifsaver;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

/**
 * Common screen behaviour: edge-to-edge padding and the optional app lock (fingerprint / face
 * / screen lock PIN). While the lock is on, screenshots and the recent-apps preview are blocked.
 */
abstract class BaseActivity extends Activity {

    private static final int REQ_UNLOCK = 41;
    /** Unlocked for this visit; locks again 30 s after leaving the app. */
    static boolean unlocked;
    private static int started;
    private static long leftAt;
    private boolean asking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Prefs.get(this).lock) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    public void setContentView(int layout) {
        super.setContentView(layout);
        View root = findViewById(R.id.root);
        if (root != null) {
            Ui.applyInsets(root);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (started++ == 0 && leftAt != 0 && SystemClock.elapsedRealtime() - leftAt > 30_000) {
            unlocked = false;
        }
    }

    @Override
    protected void onStop() {
        if (--started == 0) {
            leftAt = SystemClock.elapsedRealtime();
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.get(this).lock) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        if (needsUnlock()) {
            content(false);
            if (!asking) {
                asking = true;
                askUnlock();
            }
        } else {
            content(true);
            onUnlockedResume();
        }
    }

    /** onResume, once the screen may show its content. */
    protected void onUnlockedResume() {
    }

    private boolean needsUnlock() {
        if (!Prefs.get(this).lock || unlocked) {
            return false;
        }
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return km != null && km.isDeviceSecure(); // no screen lock set: nothing to ask for
    }

    private void content(boolean show) {
        View c = findViewById(android.R.id.content);
        if (c != null) {
            c.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void onUnlocked() {
        asking = false;
        unlocked = true;
        content(true);
        onUnlockedResume();
    }

    private void onUnlockFailed() {
        asking = false;
        finishAffinity();
    }

    @SuppressWarnings("deprecation")
    private void askUnlock() {
        String title = getString(R.string.lock_title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                    .setTitle(title)
                    .setSubtitle(getString(R.string.lock_subtitle));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                b.setDeviceCredentialAllowed(true);
            }
            try {
                b.build().authenticate(new CancellationSignal(), getMainExecutor(),
                        new BiometricPrompt.AuthenticationCallback() {
                            @Override
                            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                                onUnlocked();
                            }

                            @Override
                            public void onAuthenticationError(int code, CharSequence message) {
                                onUnlockFailed();
                            }
                        });
                return;
            } catch (RuntimeException e) {
                // fall back to the screen lock confirmation below
            }
        }
        KeyguardManager km = getSystemService(KeyguardManager.class);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(title, getString(R.string.lock_subtitle));
        if (i == null) {
            onUnlocked();
            return;
        }
        try {
            startActivityForResult(i, REQ_UNLOCK);
        } catch (RuntimeException e) {
            onUnlocked();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_UNLOCK) {
            if (resultCode == RESULT_OK) {
                onUnlocked();
            } else {
                onUnlockFailed();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}
