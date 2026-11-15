package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VideoOrientationPolicyTest {
    @Test public void landscapeMovieRotates() {
        assertEquals(Boolean.TRUE, VideoOrientationPolicy.isLandscape(1920, 1080, 0, 1));
        assertEquals(Boolean.TRUE, VideoOrientationPolicy.isLandscape(3840, 2160, 0, 1));
    }
    @Test public void shortsStayPortrait() {
        assertEquals(Boolean.FALSE, VideoOrientationPolicy.isLandscape(1080, 1920, 0, 1));
        assertEquals(Boolean.FALSE, VideoOrientationPolicy.isLandscape(720, 1280, 0, 1));
    }
    @Test public void metadataRotationSwapsDimensions() {
        assertEquals(Boolean.FALSE, VideoOrientationPolicy.isLandscape(1920, 1080, 90, 1));
        assertEquals(Boolean.TRUE, VideoOrientationPolicy.isLandscape(1080, 1920, 270, 1));
    }
    @Test public void squareStaysPortrait() {
        assertEquals(Boolean.FALSE, VideoOrientationPolicy.isLandscape(1080, 1080, 0, 1));
    }
    @Test public void unknownSizeDefersDecision() {
        assertNull(VideoOrientationPolicy.isLandscape(0, 0, 0, 1));
    }
    @Test public void pixelRatioContributesToEffectiveAspect() {
        assertEquals(Boolean.TRUE, VideoOrientationPolicy.isLandscape(960, 1080, 0, 2));
    }
}
