package com.ahmadman.watereject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Circular "sound wave" visual: a progress ring, rings expanding outward and a wavy
 * circle whose lobes follow the current frequency. Animates only while cleaning.
 */
public class WaveView extends View {

    private static final int WAVE_POINTS = 180;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final RectF arcRect = new RectF();
    private final float dp;

    private float ringPhase;
    private float wavePhase;
    private long lastFrameNanos;
    private float innerRadiusPx;

    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        int primary = context.getColor(R.color.primary);
        int soft = context.getColor(R.color.primary_soft);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(6 * dp);
        trackPaint.setColor(soft);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(6 * dp);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setColor(primary);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * dp);
        ringPaint.setColor(primary);

        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(2.5f * dp);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setColor(primary);

        innerRadiusPx = 74 * dp;
    }

    /** Radius of the button drawn on top, so the waves start just outside it. */
    void setInnerRadius(float px) {
        innerRadiusPx = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = Math.min(cx, cy) - 4 * dp;
        float bandIn = innerRadiusPx + 6 * dp;
        float bandOut = outer - 12 * dp;

        ToneEngine engine = CleanService.engine();
        boolean running = engine != null;
        float progress = running ? engine.getProgress() : 0f;
        float amp = running ? engine.getCurrentAmp() : 0f;
        float freq = running ? engine.getCurrentFreq() : ToneEngine.BASE_FREQ;

        canvas.drawCircle(cx, cy, outer, trackPaint);
        if (running && progress > 0f) {
            arcRect.set(cx - outer, cy - outer, cx + outer, cy + outer);
            canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint);
        }

        if (!running) {
            lastFrameNanos = 0;
            ringPaint.setAlpha(45);
            canvas.drawCircle(cx, cy, bandIn + (bandOut - bandIn) * 0.4f, ringPaint);
            ringPaint.setAlpha(25);
            canvas.drawCircle(cx, cy, bandIn + (bandOut - bandIn) * 0.8f, ringPaint);
            return;
        }

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : Math.min(0.05f, (now - lastFrameNanos) / 1e9f);
        lastFrameNanos = now;
        ringPhase = (ringPhase + dt * (0.45f + freq / 260f)) % 1f;
        wavePhase = (wavePhase + dt * 2.2f) % (float) (2 * Math.PI);

        // Expanding rings.
        float energy = 0.3f + 0.7f * amp;
        for (int k = 0; k < 3; k++) {
            float p = (ringPhase + k / 3f) % 1f;
            float r = bandIn + p * (bandOut - bandIn);
            ringPaint.setAlpha((int) (190 * (1f - p) * energy));
            canvas.drawCircle(cx, cy, r, ringPaint);
        }

        // Wavy circle: more lobes for higher frequencies.
        float mid = bandIn + (bandOut - bandIn) * 0.42f;
        int lobes = Math.max(5, Math.min(18, Math.round(freq / 22f)));
        float depth = (bandOut - bandIn) * 0.16f * amp;
        wavePath.reset();
        for (int i = 0; i <= WAVE_POINTS; i++) {
            double th = 2 * Math.PI * i / WAVE_POINTS;
            double r = mid + depth * Math.sin(lobes * th + wavePhase * 3);
            float x = (float) (cx + r * Math.cos(th));
            float y = (float) (cy + r * Math.sin(th));
            if (i == 0) {
                wavePath.moveTo(x, y);
            } else {
                wavePath.lineTo(x, y);
            }
        }
        wavePath.close();
        wavePaint.setAlpha((int) (110 + 120 * amp));
        canvas.drawPath(wavePath, wavePaint);

        postInvalidateOnAnimation();
    }
}
