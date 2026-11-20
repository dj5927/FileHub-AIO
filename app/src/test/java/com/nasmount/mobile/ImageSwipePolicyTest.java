package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ImageSwipePolicyTest {
    @Test public void horizontalSwipeAtFitScaleChangesPhotos() {
        assertEquals(1, ImageSwipePolicy.direction(-195, 12, 1000, 2, 1, false));
        assertEquals(-1, ImageSwipePolicy.direction(195, -12, 1000, 2, 1, false));
    }
    @Test public void verticalTapAndSlowDragDoNotChangePhotos() {
        assertEquals(0, ImageSwipePolicy.direction(0, 330, 1000, 2, 1, false));
        assertEquals(0, ImageSwipePolicy.direction(48, 2, 1000, 2, 1, false));
        assertEquals(0, ImageSwipePolicy.direction(175, 180, 1000, 2, 1, false));
    }
    @Test public void panPinchAndTwoFingerGesturesCannotChangePhotos() {
        assertEquals(0, ImageSwipePolicy.direction(-300, 0, 1000, 2, 2.2f, false));
        assertEquals(0, ImageSwipePolicy.direction(-300, 0, 1000, 2, 1, true));
    }
}
