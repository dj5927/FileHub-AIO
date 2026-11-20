package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImagePagerMotionPolicyTest {
    @Test public void fingerDragTracksPhotoAndAdjacentPhotoWithoutGap() {
        assertEquals(-315f, ImagePagerMotionPolicy.offset(-315, 1000, true), 0.001f);
        assertEquals(286f, ImagePagerMotionPolicy.offset(286, 1000, true), 0.001f);
        assertEquals(-1000f, ImagePagerMotionPolicy.offset(-1200, 1000, true), 0.001f);
        // An unloaded NAS neighbor must never create a black rectangle.
        assertEquals(0f, ImagePagerMotionPolicy.offset(-800, 1000, false), 0.001f);
        assertEquals(0f, ImagePagerMotionPolicy.offset(500, 1000, false), 0.001f);
        // Both neighboring photos follow the finger without a gap.
        for(int x=-1000;x<=1000;x+=13) {
            assertEquals(1000f, ImagePagerMotionPolicy.neighborOffset(
                    x,1000,1)-x,0.001f);
            assertEquals(-1000f, ImagePagerMotionPolicy.neighborOffset(
                    x,1000,-1)-x,0.001f);
        }
        assertEquals(0f, ImagePagerMotionPolicy.neighborOffset(
                -1000,1000,1),0.001f);
        assertEquals(0f, ImagePagerMotionPolicy.neighborOffset(
                1000,1000,-1),0.001f);
    }

    @Test public void distanceAndQuickFlickCommitNaturalPageDirection() {
        assertEquals(1, ImagePagerMotionPolicy.releaseDirection(
                -270, 4, -400, 1000, 2, 1, false));
        assertEquals(-1, ImagePagerMotionPolicy.releaseDirection(
                260, 4, 300, 1000, 2, 1, false));
        assertEquals(1, ImagePagerMotionPolicy.releaseDirection(
                -56, 3, -1850, 1000, 2, 1, false));
        assertEquals(0, ImagePagerMotionPolicy.releaseDirection(
                -56, 3, 1850, 1000, 2, 1, false));
    }

    @Test public void zoomPinchVerticalAndShortDragNeverFlipPages() {
        assertEquals(0, ImagePagerMotionPolicy.releaseDirection(
                -470, 1, -2000, 1000, 2, 2.5f, false));
        assertEquals(0, ImagePagerMotionPolicy.releaseDirection(
                -470, 1, -2000, 1000, 2, 1, true));
        assertEquals(0, ImagePagerMotionPolicy.releaseDirection(
                -175, 210, -3000, 1000, 2, 1, false));
        assertEquals(0, ImagePagerMotionPolicy.releaseDirection(
                -35, 3, 0, 1000, 2, 1, false));
    }

    @Test public void shortCancelledAndFullFlickAnimationsHaveBoundedDuration() {
        assertTrue(ImagePagerMotionPolicy.durationMs(-50, 0, 1000) >= 130);
        assertTrue(ImagePagerMotionPolicy.durationMs(-50, -1000, 1000) <= 280);
        assertEquals(200, ImagePagerMotionPolicy.durationMs(0, 1000, 0));
    }
}
