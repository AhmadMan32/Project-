package com.ahmadman.kenarpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import com.ahmadman.kenarpanel.atv.Touchpad;

/**
 * The Android TV touchpad: swipe to move (one arrow step per short distance), tap to select.
 * The dot follows the finger and springs back to the middle when let go.
 */
public class TouchpadView extends View {

    interface Keys {
        void step(Touchpad.Step s);

        void select();
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Touchpad pad;
    private final float dp;
    private Keys keys;
    private boolean haptics = true;
    private float dotX = Float.NaN;
    private float dotY = Float.NaN;
    private float pulse;
    private Tween back;
    private Tween pulseAnim;
    private String hintTop;
    private String hintBottom;

    public TouchpadView(Context c) {
        this(c, null);
    }

    public TouchpadView(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        pad = new Touchpad(26 * dp, 8 * dp);
        bg.setColor(c.getColor(R.color.panel_pad));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp);
        edge.setColor(c.getColor(R.color.panel_stroke));
        dot.setColor(c.getColor(R.color.panel_dot));
        hint.setColor(c.getColor(R.color.panel_text_dim));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(10 * dp);
        hintTop = c.getString(R.string.pad_hint_swipe);
        hintBottom = c.getString(R.string.pad_hint_tap);
        setClickable(true);
        setFocusable(true);
        setContentDescription(c.getString(R.string.pad_description));
    }

    /** Takes the space its parent gives; asks for little on its own (so it never grows the card). */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int min = Math.round(40 * dp);
        setMeasuredDimension(resolveSize(min, widthSpec), resolveSize(min, heightSpec));
    }

    void setKeys(Keys k) {
        keys = k;
    }

    void setHaptics(boolean on) {
        haptics = on;
    }

    @Override
    protected void onDraw(Canvas c) {
        float r = 20 * dp;
        box.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(box, r, r, bg);
        box.inset(dp / 2, dp / 2);
        c.drawRoundRect(box, r, r, edge);
        float cx = Float.isNaN(dotX) ? getWidth() / 2f : dotX;
        float cy = Float.isNaN(dotY) ? getHeight() / 2f - 6 * dp : dotY;
        c.drawCircle(cx, cy, (11 + 5 * pulse) * dp, dot);
        if (getHeight() > 70 * dp) {
            c.drawText(hintTop, getWidth() / 2f, getHeight() - 20 * dp, hint);
            c.drawText(hintBottom, getWidth() / 2f, getHeight() - 8 * dp, hint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (back != null) {
                    back.cancel();
                }
                pad.down(e.getX(), e.getY(), e.getEventTime());
                dotX = e.getX();
                dotY = e.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                pad.move(e.getX(), e.getY(), s -> {
                    if (keys != null) {
                        keys.step(s);
                    }
                    if (haptics) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                });
                dotX = clampX(e.getX());
                dotY = clampY(e.getY());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (pad.up(e.getX(), e.getY(), e.getEventTime())) {
                    performClick();
                }
                springBack();
                return true;
            case MotionEvent.ACTION_CANCEL:
                springBack();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (keys != null) {
            keys.select();
        }
        if (haptics) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        if (pulseAnim != null) {
            pulseAnim.cancel();
        }
        pulseAnim = Tween.run(1f, 0f, 260, null, v -> {
            pulse = v;
            invalidate();
        }, null);
        return true;
    }

    private float clampX(float x) {
        return Math.max(12 * dp, Math.min(getWidth() - 12 * dp, x));
    }

    private float clampY(float y) {
        return Math.max(12 * dp, Math.min(getHeight() - 12 * dp, y));
    }

    private void springBack() {
        float fromX = Float.isNaN(dotX) ? getWidth() / 2f : dotX;
        float fromY = Float.isNaN(dotY) ? getHeight() / 2f : dotY;
        float toX = getWidth() / 2f;
        float toY = getHeight() / 2f - 6 * dp;
        back = Tween.run(0f, 1f, 320, new OvershootInterpolator(1.6f), v -> {
            dotX = fromX + (toX - fromX) * v;
            dotY = fromY + (toY - fromY) * v;
            invalidate();
        }, () -> {
            dotX = Float.NaN;
            dotY = Float.NaN;
            invalidate();
        });
    }

    /** For tests: where the dot is drawn now. */
    float dotX() {
        return Float.isNaN(dotX) ? getWidth() / 2f : dotX;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
    }
}
