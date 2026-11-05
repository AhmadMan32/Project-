package com.ahmadman.kenarpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * The thin bar at the screen edge; its window is wider than the bar so it is easy to grab.
 * While held it grows thicker and brighter, and springs back when let go.
 */
public class HandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private boolean right;
    private float thickness;
    private int opacity = 70;
    private float grow;
    private Tween growAnim;

    public HandleView(Context c) {
        super(c);
        paint.setColor(0xFFFFFFFF);
        setContentDescription(c.getString(R.string.handle_description));
    }

    void style(boolean right, float thicknessPx, int opacityPercent) {
        this.right = right;
        this.thickness = thicknessPx;
        this.opacity = opacityPercent;
        invalidate();
    }

    /** Held (true) or let go (false); {@code millis} 0 = no animation. */
    void grab(boolean held, int millis) {
        if (growAnim != null) {
            growAnim.cancel();
        }
        float target = held ? 1f : 0f;
        if (millis <= 0) {
            grow = target;
            invalidate();
            return;
        }
        growAnim = Tween.run(grow, target, held ? millis / 2 : millis,
                held ? new DecelerateInterpolator() : new OvershootInterpolator(2f), v -> {
                    grow = v;
                    invalidate();
                }, null);
    }

    float grow() {
        return grow;
    }

    @Override
    public boolean performClick() {
        return super.performClick(); // a tap opens the panel (see OverlayService)
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float inset = 3 * d;
        float t = thickness * (1f + 0.7f * grow);
        float left = right ? getWidth() - inset - t : inset;
        bar.set(left, 0, left + t, getHeight());
        float alpha = opacity / 100f + (1f - opacity / 100f) * grow;
        paint.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
        paint.setShadowLayer(t, 0, 0, 0x55000000);
        c.drawRoundRect(bar, t / 2f, t / 2f, paint);
    }
}
