package com.ahmadman.kenarpanel.atv;

/**
 * Turns finger movement on the touchpad into arrow-key steps (one step per {@code stepPx} of
 * travel, along the dominant direction) and a short tap without movement into "select".
 */
public final class Touchpad {

    public enum Step { UP, DOWN, LEFT, RIGHT }

    public interface Out {
        void step(Step s);
    }

    private final float stepPx;
    private final float slopPx;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private long downTime;
    private float accX;
    private float accY;
    private boolean moved;

    public Touchpad(float stepPx, float slopPx) {
        this.stepPx = stepPx;
        this.slopPx = slopPx;
    }

    public void down(float x, float y, long time) {
        lastX = downX = x;
        lastY = downY = y;
        downTime = time;
        accX = 0;
        accY = 0;
        moved = false;
    }

    public void move(float x, float y, Out out) {
        accX += x - lastX;
        accY += y - lastY;
        lastX = x;
        lastY = y;
        if (!moved && Math.hypot(x - downX, y - downY) > slopPx) {
            moved = true;
        }
        while (Math.abs(accX) >= stepPx || Math.abs(accY) >= stepPx) {
            if (Math.abs(accX) >= Math.abs(accY)) {
                out.step(accX > 0 ? Step.RIGHT : Step.LEFT);
                accX -= Math.signum(accX) * stepPx;
                accY = 0; // stick to one axis per step: no diagonal double moves
            } else {
                out.step(accY > 0 ? Step.DOWN : Step.UP);
                accY -= Math.signum(accY) * stepPx;
                accX = 0;
            }
            moved = true;
        }
    }

    /** @return true when this was a tap (select) */
    public boolean up(float x, float y, long time) {
        return !moved && Math.hypot(x - downX, y - downY) <= slopPx && time - downTime < 500;
    }
}
