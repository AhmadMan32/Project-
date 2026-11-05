package com.ahmadman.kenarpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The thin bar at the screen edge; its window is wider than the bar so it is easy to grab. */
public class HandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private boolean right;
    private float thickness;

    public HandleView(Context c) {
        super(c);
        paint.setColor(0xFFFFFFFF);
        setContentDescription(c.getString(R.string.handle_description));
    }

    void style(boolean right, float thicknessPx, int opacityPercent) {
        this.right = right;
        this.thickness = thicknessPx;
        paint.setAlpha(Math.round(255 * opacityPercent / 100f));
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick(); // a tap opens the panel (see OverlayService)
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = 3 * getResources().getDisplayMetrics().density;
        float left = right ? getWidth() - inset - thickness : inset;
        bar.set(left, 0, left + thickness, getHeight());
        // soft shadow so the bar shows on light screens too
        paint.setShadowLayer(thickness, 0, 0, 0x55000000);
        c.drawRoundRect(bar, thickness / 2f, thickness / 2f, paint);
    }
}
