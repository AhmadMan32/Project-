package com.ahmadman.speedmeter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.ahmadman.speedmeter.core.SpeedFormat;

import java.util.Locale;

/**
 * The meter itself: "↓ 2.3 Mbps  ↑ 859 kbps" with optional speed bars behind the values.
 * Space for the widest value is reserved so the meter does not jump while numbers change.
 */
public class SpeedView extends View {

    static final int DOWN_COLOR = 0xFF2563EB;
    static final int UP_COLOR = 0xFFDC2626;
    static final int TOTAL_COLOR = 0xFF7C3AED;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Style style = new Style();
    private double down;
    private double up;
    private boolean supported = true;
    /** The overlay applies opacity to its window instead (Android 12+ touch rules). */
    private boolean opacityOnView = true;

    private float arrowW;
    private float valueW;
    private float gap;
    private float padH;
    private float padV;
    private float lineH;

    public SpeedView(Context c) {
        this(c, null);
    }

    public SpeedView(Context c, AttributeSet attrs) {
        super(c, attrs);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        applyStyle();
    }

    void setStyle(Style s) {
        style = s;
        applyStyle();
        requestLayout();
        invalidate();
    }

    void setOpacityOnView(boolean onView) {
        opacityOnView = onView;
        applyStyle();
    }

    void setSpeeds(double downBytesPerSec, double upBytesPerSec, boolean isSupported) {
        down = downBytesPerSec;
        up = upBytesPerSec;
        supported = isSupported;
        invalidate();
    }

    private int items() {
        return style.showTotal() ? 1 : (style.showDown() ? 1 : 0) + (style.showUp() ? 1 : 0);
    }

    private void applyStyle() {
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.sizeSp, getResources().getDisplayMetrics());
        text.setTextSize(px);
        boolean light = Style.BG_LIGHT.equals(style.background);
        int color = style.darkText || light ? 0xFF111111 : Color.WHITE;
        text.setColor(color);
        if (Style.BG_NONE.equals(style.background)) {
            // readable on any wallpaper
            text.setShadowLayer(px * 0.18f, 0, px * 0.05f, color == Color.WHITE ? 0xCC000000 : 0x99FFFFFF);
        } else {
            text.clearShadowLayer();
        }
        bg.setColor(light ? 0xE6FFFFFF : 0x99000000);
        arrowW = text.measureText(style.showTotal() ? "⇅ " : "↓ ");
        String sample = style.bits ? "888 Mbps" : "888 MB/s";
        valueW = Math.max(text.measureText(sample), text.measureText(style.bits ? "8.8 kbps" : "8.8 KB/s"));
        gap = px * 0.9f;
        boolean hasBg = !Style.BG_NONE.equals(style.background);
        padH = hasBg ? px * 0.55f : px * 0.25f;
        padV = hasBg ? px * 0.3f : px * 0.2f;
        lineH = px * 1.35f;
        setAlpha(opacityOnView ? style.opacity / 100f : 1f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int n = Math.max(1, items());
        float itemW = arrowW + valueW;
        float w = style.column ? itemW : itemW * n + gap * (n - 1);
        float h = style.column ? lineH * n : lineH;
        setMeasuredDimension((int) Math.ceil(w + 2 * padH), (int) Math.ceil(h + 2 * padV));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!Style.BG_NONE.equals(style.background)) {
            rect.set(0, 0, getWidth(), getHeight());
            float r = Math.min(getHeight() / 2f, text.getTextSize() * 0.8f);
            canvas.drawRoundRect(rect, r, r, bg);
        }
        float x = padH;
        float y = padV;
        if (style.showTotal()) {
            item(canvas, x, y, "⇅ ", down + up, TOTAL_COLOR);
            return;
        }
        if (style.showDown()) {
            item(canvas, x, y, "↓ ", down, DOWN_COLOR);
            if (style.column) {
                y += lineH;
            } else {
                x += arrowW + valueW + gap;
            }
        }
        if (style.showUp()) {
            item(canvas, x, y, "↑ ", up, UP_COLOR);
        }
    }

    private void item(Canvas canvas, float x, float top, String arrow, double bytesPerSec, int color) {
        float baseline = top + (lineH - (text.descent() + text.ascent())) / 2f;
        float vx = x + arrowW;
        if (style.bars && supported) {
            float level = SpeedFormat.level(bytesPerSec);
            if (level > 0) {
                float barW = Math.max(text.getTextSize() * 0.25f, valueW * level);
                float barTop = top + lineH * 0.12f;
                float barBottom = top + lineH * 0.88f;
                rect.set(vx - text.getTextSize() * 0.1f, barTop, vx + barW, barBottom);
                bar.setColor(color);
                bar.setAlpha(Style.BG_LIGHT.equals(style.background) ? 110 : 215);
                float r = text.getTextSize() * 0.18f;
                canvas.drawRoundRect(rect, r, r, bar);
            }
        }
        canvas.drawText(arrow, x, baseline, text);
        String value = supported ? SpeedFormat.format(bytesPerSec, style.bits, Locale.getDefault()) : "—";
        canvas.drawText(value, vx, baseline, text);
    }
}
