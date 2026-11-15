package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackControlPolicyTest {
    @Test public void speedIncreasesAndDecreasesByExactlyOneTenth() {
        int speed = PlaybackControlPolicy.DEFAULT_SPEED_TENTHS;
        speed = PlaybackControlPolicy.adjustSpeed(speed, 1);
        assertEquals("1.1×", PlaybackControlPolicy.speedLabel(speed));
        speed = PlaybackControlPolicy.adjustSpeed(speed, -1);
        assertEquals(10, speed);
        assertEquals("1.0×", PlaybackControlPolicy.speedLabel(speed));
    }

    @Test public void speedBoundsPreventInvalidNegativeOrHugeValues() {
        assertEquals(5, PlaybackControlPolicy.adjustSpeed(5, -1));
        assertEquals(30, PlaybackControlPolicy.adjustSpeed(30, 1));
        assertEquals(0.5f, PlaybackControlPolicy.toSpeed(0), 0.0001f);
        assertEquals(3.0f, PlaybackControlPolicy.toSpeed(100), 0.0001f);
    }

    @Test public void decimalSpeedConvertsWithoutAccumulatedDrift() {
        assertEquals(17, PlaybackControlPolicy.toTenths(1.6999999f));
        assertEquals("1.7×", PlaybackControlPolicy.speedLabel(17));
    }

    @Test public void orientationCyclesAutoLandscapePortraitAuto() {
        int mode = PlaybackControlPolicy.AUTO;
        mode = PlaybackControlPolicy.nextOrientation(mode);
        assertEquals(PlaybackControlPolicy.LANDSCAPE, mode);
        mode = PlaybackControlPolicy.nextOrientation(mode);
        assertEquals(PlaybackControlPolicy.PORTRAIT, mode);
        mode = PlaybackControlPolicy.nextOrientation(mode);
        assertEquals(PlaybackControlPolicy.AUTO, mode);
    }

    @Test public void orientationLabelsMatchTheMode() {
        assertEquals("자동", PlaybackControlPolicy.orientationLabel(PlaybackControlPolicy.AUTO));
        assertEquals("가로", PlaybackControlPolicy.orientationLabel(PlaybackControlPolicy.LANDSCAPE));
        assertEquals("세로", PlaybackControlPolicy.orientationLabel(PlaybackControlPolicy.PORTRAIT));
    }
}
