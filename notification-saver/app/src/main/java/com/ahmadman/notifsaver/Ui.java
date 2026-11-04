package com.ahmadman.notifsaver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Locale;

/** Small shared view helpers. */
final class Ui {

    private Ui() {
    }

    private static final int[] LIGHT = {0xFF1E73D8, 0xFFC2185B, 0xFF2E7D32, 0xFFE65100, 0xFF7B1FA2, 0xFF00796B, 0xFF5D4037,
            0xFF3949AB};
    private static final int[] DARK = {0xFF64B5F6, 0xFFF48FB1, 0xFF81C784, 0xFFFFB74D, 0xFFCE93D8, 0xFF4DB6AC, 0xFFBCAAA4,
            0xFF9FA8DA};

    static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** A stable color per name (chat senders, letter avatars). */
    static int colorFor(Context c, String name) {
        int[] p = night(c) ? DARK : LIGHT;
        int h = name == null ? 0 : name.hashCode();
        return p[(h & 0x7fffffff) % p.length];
    }

    static String number(long n) {
        return NumberFormat.getIntegerInstance(Locale.getDefault()).format(n);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void text(TextView v, CharSequence s) {
        v.setText(s);
        v.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    static boolean open(Activity a, Intent i) {
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Pads the root view for status bar, navigation bar, cutout and keyboard (edge-to-edge). */
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
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                il = i.left;
                it = i.top;
                ir = i.right;
                ib = Math.max(i.bottom, ime.bottom);
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

    /** Sets up the shared top bar. */
    static void topBar(Activity a, CharSequence title, CharSequence subtitle, boolean back) {
        ((TextView) a.findViewById(R.id.title)).setText(title);
        text(a.findViewById(R.id.subtitle), subtitle);
        View b = a.findViewById(R.id.back);
        b.setVisibility(back ? View.VISIBLE : View.GONE);
        b.setOnClickListener(v -> a.finish());
    }

    static ImageView action(Activity a, int id, int icon, int description, View.OnClickListener l) {
        ImageView v = a.findViewById(id);
        v.setImageResource(icon);
        v.setContentDescription(a.getString(description));
        v.setOnClickListener(l);
        v.setVisibility(View.VISIBLE);
        return v;
    }

    /** Round colored avatar with the first letter, used when an app icon is not available. */
    static final class Letter extends Drawable {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String letter;

        Letter(Context c, String name) {
            circle.setColor(colorFor(c, name));
            text.setColor(night(c) ? 0xFF0D1110 : 0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            String n = name == null ? "" : name.trim();
            letter = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, circle);
            text.setTextSize(r * 0.95f);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(letter, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, text);
        }

        @Override
        public void setAlpha(int alpha) {
            circle.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            circle.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
