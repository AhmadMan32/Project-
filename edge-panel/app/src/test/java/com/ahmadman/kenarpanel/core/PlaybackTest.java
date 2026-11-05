package com.ahmadman.kenarpanel.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaybackTest {

    @Test
    public void positionMovesOnWhilePlaying() {
        assertEquals(65_000, Playback.position(60_000, 1_000, 1f, true, 6_000, 200_000));
        assertEquals(60_000, Playback.position(60_000, 1_000, 1f, false, 6_000, 200_000)); // paused: stays
        assertEquals(70_000, Playback.position(60_000, 1_000, 2f, true, 6_000, 200_000));  // 2x speed
        assertEquals(200_000, Playback.position(199_000, 1_000, 1f, true, 9_000, 200_000)); // not past the end
        assertEquals(0, Playback.position(-5, 0, 1f, false, 10, 0));
    }

    @Test
    public void timeLabels() {
        assertEquals("0:00", Playback.time(0));
        assertEquals("3:07", Playback.time(187_400));
        assertEquals("1:02:05", Playback.time(3_725_000));
    }
}
