package com.ahmadman.podpil;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.ahmadman.podpil.core.Status;

/**
 * Left pod, right pod and case, each inside a ring that fills with its battery level; the
 * percentage, charging bolt and "in ear" mark below.
 */
public class PodsView extends View {

    private Status status;
    private boolean compact;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final RectF rect = new RectF();
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float dp;

    public PodsView(Context c) {
        this(c, null);
    }

    public PodsView(Context c, AttributeSet attrs) {
        super(c, attrs);
        dp = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(c.getColor(R.color.ring_track));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        value.setTextAlign(Paint.Align.CENTER);
        value.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        value.setColor(c.getColor(R.color.text_primary));
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(c.getColor(R.color.text_secondary));
        body.setColor(c.getColor(R.color.pod_body));
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(c.getColor(R.color.pod_edge));
        detail.setColor(c.getColor(R.color.pod_detail));
        if (isInEditMode()) {
            Status s = new Status();
            s.modelId = 0x1420;
            s.left = 80;
            s.right = 75;
            s.caseLevel = 40;
            s.caseCharging = true;
            status = s;
        }
    }

    public void setStatus(Status s) {
        status = s;
        invalidate();
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        // ring + percentage + label, the same arithmetic as onDraw with the largest ring
        float r = (compact ? 40 : 50) * dp;
        float stroke = (compact ? 5 : 6) * dp;
        float valueSize = (compact ? 18 : 22) * dp * fontScale();
        float labelSize = (compact ? 12 : 13) * dp * fontScale();
        int h = Math.round(6 * dp + 2 * r + stroke + valueSize + 4 * dp + labelSize + 6 * dp + labelSize * 0.4f + 4 * dp)
                + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(w, resolveSize(h, heightSpec));
    }

    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    private static final int CASE = 2;
    private static final int HEADPHONES = 3;
    private static final int[] DUAL = {LEFT, RIGHT, CASE};
    private static final int[] SINGLE = {HEADPHONES};

    @Override
    protected void onDraw(Canvas canvas) {
        Status s = status;
        if (s == null) {
            return;
        }
        boolean single = s.model().single;
        int[] kinds = single ? SINGLE : DUAL;
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float colW = Math.min(w / kinds.length, (compact ? 120 : 140) * dp);
        float start = getPaddingLeft() + (w - colW * kinds.length) / 2f;
        float r = Math.min(colW * 0.36f, (compact ? 40 : 50) * dp);
        float cy = getPaddingTop() + r + 6 * dp;
        float stroke = (compact ? 5 : 6) * dp;
        track.setStrokeWidth(stroke);
        ring.setStrokeWidth(stroke);
        edge.setStrokeWidth(1.2f * dp);
        value.setTextSize((compact ? 18 : 22) * dp * fontScale());
        label.setTextSize((compact ? 12 : 13) * dp * fontScale());
        for (int i = 0; i < kinds.length; i++) {
            float cx = start + colW * (i + 0.5f);
            int k = kinds[i];
            int level = k == RIGHT ? s.right : k == CASE ? s.caseLevel : s.left;
            boolean charging = k == RIGHT ? s.rightCharging : k == CASE ? s.caseCharging : s.leftCharging;
            boolean inEar = k == LEFT ? s.leftInEar : k == RIGHT && s.rightInEar;

            canvas.drawCircle(cx, cy, r, track);
            if (level != Status.UNKNOWN) {
                ring.setColor(levelColor(level, charging));
                oval.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(oval, -90, Math.max(2, level * 3.6f), false, ring);
            }
            int alpha = level == Status.UNKNOWN ? 110 : 255;
            body.setAlpha(alpha);
            edge.setAlpha(alpha);
            detail.setAlpha(alpha);
            float u = r * 0.62f;
            if (k == CASE) {
                drawCase(canvas, cx, cy, u, charging);
            } else if (k == HEADPHONES) {
                drawHeadphones(canvas, cx, cy, u);
            } else {
                drawBud(canvas, cx, cy, u, k == RIGHT);
            }

            float ty = cy + r + stroke + value.getTextSize() + 4 * dp;
            String text = Texts.pct(getContext(), level);
            canvas.drawText(text, cx, ty, value);
            if (charging && level != Status.UNKNOWN) {
                float tw = value.measureText(text);
                drawBolt(canvas, cx + tw / 2f + 8 * dp, ty - value.getTextSize() * 0.36f, value.getTextSize() * 0.42f,
                        levelColor(100, true));
            }
            String name = getContext().getString(k == LEFT ? R.string.left : k == RIGHT ? R.string.right
                    : k == CASE ? R.string.case_label : R.string.headphones);
            if (inEar) {
                name += " · " + getContext().getString(R.string.in_ear);
            }
            canvas.drawText(name, cx, ty + label.getTextSize() + 6 * dp, label);
        }
    }

    private float fontScale() {
        return Math.min(1.3f, getResources().getConfiguration().fontScale);
    }

    int levelColor(int level, boolean charging) {
        Context c = getContext();
        if (charging || level > 20) {
            return c.getColor(R.color.battery_ok);
        }
        return c.getColor(level > 10 ? R.color.battery_mid : R.color.battery_low);
    }

    /** A generic earbud: round head with a speaker opening and a stem. */
    private void drawBud(Canvas c, float cx, float cy, float u, boolean right) {
        c.save();
        c.translate(cx, cy);
        if (right) {
            c.scale(-1, 1);
        }
        oval.set(-0.30f * u, -0.80f * u, 0.52f * u, -0.04f * u);
        c.drawOval(oval, body);
        c.drawOval(oval, edge);
        rect.set(0.04f * u, -0.30f * u, 0.30f * u, 0.86f * u);
        c.drawRoundRect(rect, 0.13f * u, 0.13f * u, body);
        c.drawRoundRect(rect, 0.13f * u, 0.13f * u, edge);
        oval.set(-0.29f * u, -0.80f * u, 0.51f * u, -0.05f * u);
        c.drawOval(oval, body); // hides the stem's top edge inside the head
        oval.set(-0.22f * u, -0.56f * u, 0.02f * u, -0.38f * u);
        c.drawOval(oval, detail); // speaker
        rect.set(0.09f * u, 0.70f * u, 0.25f * u, 0.80f * u);
        c.drawRoundRect(rect, 0.04f * u, 0.04f * u, detail);
        c.restore();
    }

    private void drawCase(Canvas c, float cx, float cy, float u, boolean charging) {
        rect.set(cx - 0.78f * u, cy - 0.58f * u, cx + 0.78f * u, cy + 0.62f * u);
        c.drawRoundRect(rect, 0.32f * u, 0.32f * u, body);
        c.drawRoundRect(rect, 0.32f * u, 0.32f * u, edge);
        c.drawLine(rect.left + 0.06f * u, cy - 0.16f * u, rect.right - 0.06f * u, cy - 0.16f * u, edge);
        fill.setColor(charging ? getContext().getColor(R.color.battery_ok) : getContext().getColor(R.color.pod_edge));
        c.drawCircle(cx, cy + 0.18f * u, 0.07f * u, fill);
    }

    private void drawHeadphones(Canvas c, float cx, float cy, float u) {
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeWidth(0.16f * u);
        band.setStrokeCap(Paint.Cap.ROUND);
        band.setColor(getContext().getColor(R.color.pod_detail));
        oval.set(cx - 0.66f * u, cy - 0.78f * u, cx + 0.66f * u, cy + 0.54f * u);
        c.drawArc(oval, 195, 150, false, band);
        for (int side = -1; side <= 1; side += 2) {
            rect.set(cx + side * 0.66f * u - 0.22f * u, cy - 0.12f * u, cx + side * 0.66f * u + 0.22f * u,
                    cy + 0.66f * u);
            c.drawRoundRect(rect, 0.18f * u, 0.18f * u, body);
            c.drawRoundRect(rect, 0.18f * u, 0.18f * u, edge);
        }
    }

    private void drawBolt(Canvas c, float x, float y, float h, int color) {
        fill.setColor(color);
        path.reset();
        path.moveTo(x + 0.15f * h, y - h);
        path.lineTo(x - 0.45f * h, y + 0.1f * h);
        path.lineTo(x - 0.02f * h, y + 0.1f * h);
        path.lineTo(x - 0.18f * h, y + h);
        path.lineTo(x + 0.45f * h, y - 0.15f * h);
        path.lineTo(x + 0.02f * h, y - 0.15f * h);
        path.close();
        c.drawPath(path, fill);
    }
}
