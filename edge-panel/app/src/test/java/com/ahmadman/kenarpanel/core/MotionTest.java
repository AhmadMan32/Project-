package com.ahmadman.kenarpanel.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionTest {

    @Test
    public void springStartsEndsAndOvershootsSlightly() {
        assertEquals(0f, Motion.spring(0f), 0f);
        assertEquals(1f, Motion.spring(1f), 0f);
        float max = 0;
        float prev = 0;
        boolean rising = true;
        for (int i = 1; i < 1000; i++) {
            float v = Motion.spring(i / 1000f);
            max = Math.max(max, v);
            if (i < 100) {
                rising &= v >= prev; // moves right away, no slow start
            }
            prev = v;
        }
        assertTrue(rising);
        assertTrue(max > 1.01f && max < 1.05f); // a small bounce, ~3 %
        assertEquals(1f, Motion.spring(0.95f), 0.01f); // settled before the end
        assertTrue(Motion.spring(0.2f) > 0.5f); // fast: half way within the first fifth
    }

    @Test
    public void cascadeComesInOrder() {
        int n = 8;
        float p = 0.3f;
        float prev = 2;
        for (int i = 0; i < n; i++) {
            float c = Motion.cascade(p, i, n);
            assertTrue(c <= prev);
            prev = c;
        }
        for (int i = 0; i < n; i++) {
            assertEquals(1f, Motion.cascade(1f, i, n), 0f); // all fully there when open
            assertEquals(0f, Motion.cascade(0f, i, n), 0f);
        }
        assertEquals(0.4f, Motion.cascade(0.4f, 0, 1), 1e-6);
    }

    @Test
    public void pullDecisionAndRubberBand() {
        assertTrue(Motion.settleOpen(0.6f, 0));
        assertFalse(Motion.settleOpen(0.4f, 0));
        assertTrue(Motion.settleOpen(0.2f, 900));   // quick flick inwards
        assertFalse(Motion.settleOpen(0.8f, -900)); // quick flick back
        assertEquals(0.5f, Motion.rubberBand(0.5f), 0f);
        assertEquals(1.03f, Motion.rubberBand(1.2f), 1e-5);
        assertEquals(0f, Motion.rubberBand(-0.3f), 0f);
    }

    @Test
    public void refreshRateChoice() {
        assertEquals(90f, Motion.refreshRate(new float[]{60f, 90f, 120f}, 90f), 0f);
        assertEquals(120f, Motion.refreshRate(new float[]{60f, 120f}, 90f), 0f);
        assertEquals(90f, Motion.refreshRate(new float[]{60f, 89.9f + 0.1f}, 90f), 0f);
        assertEquals(0f, Motion.refreshRate(new float[]{60f}, 90f), 0f);
        assertEquals(0f, Motion.refreshRate(null, 90f), 0f);
    }

    @Test
    public void speeds() {
        assertEquals(Motion.Speed.FAST, Motion.Speed.of("nope"));
        assertEquals(Motion.Speed.OFF, Motion.Speed.of("OFF"));
        assertTrue(Motion.Speed.FAST.millis < Motion.Speed.MEDIUM.millis);
        assertEquals(0, Motion.Speed.OFF.millis);
    }
}
