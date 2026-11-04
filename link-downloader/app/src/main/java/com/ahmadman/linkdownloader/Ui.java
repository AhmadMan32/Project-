package com.ahmadman.linkdownloader;

import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import java.util.Locale;

/** Small UI helpers. */
final class Ui {

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static String size(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** Edge-to-edge (enforced on Android 15): pad the root by the system bars and keyboard. */
    @SuppressWarnings("deprecation")
    static void applyInsets(View root) {
        final int l = root.getPaddingLeft();
        final int t = root.getPaddingTop();
        final int r = root.getPaddingRight();
        final int b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int il;
            int it;
            int ir;
            int ib;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                if (Build.VERSION.SDK_INT >= 35) {
                    types |= WindowInsets.Type.ime();
                }
                Insets i = insets.getInsets(types);
                il = i.left;
                it = i.top;
                ir = i.right;
                ib = i.bottom;
            } else {
                il = insets.getSystemWindowInsetLeft();
                it = insets.getSystemWindowInsetTop();
                ir = insets.getSystemWindowInsetRight();
                ib = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return insets;
        });
    }
}
