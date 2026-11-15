package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoSwipePolicyTest {
    @Test public void leftSwipeUpIncreasesBrightness() {
        VideoSwipePolicy g = new VideoSwipePolicy();
        g.begin(120, 300, 1080, 1000, 100, 150);
        assertFalse(g.update(123, 296, 10));
        assertTrue(g.update(124, 200, 10));
        assertEquals(VideoSwipePolicy.BRIGHTNESS, g.side());
        assertTrue(g.amount(200) > 0);
    }
    @Test public void rightSwipeDownDecreasesVolume() {
        VideoSwipePolicy g = new VideoSwipePolicy();
        g.begin(880, 280, 1080, 1000, 100, 150);
        assertTrue(g.update(875, 370, 10));
        assertEquals(VideoSwipePolicy.VOLUME, g.side());
        assertTrue(g.amount(370) < 0);
    }
    @Test public void horizontalSwipeDoesNotCapture() {
        VideoSwipePolicy g = new VideoSwipePolicy();
        g.begin(100, 300, 1000, 1000, 100, 100);
        assertFalse(g.update(180, 303, 10));
        assertFalse(g.update(185, 150, 10));
    }
    @Test public void upperAndLowerControlsAreProtected() {
        VideoSwipePolicy top = new VideoSwipePolicy();
        top.begin(200, 10, 1000, 1000, 120, 180);
        assertFalse(top.update(200, 150, 10));
        VideoSwipePolicy bottom = new VideoSwipePolicy();
        bottom.begin(800, 920, 1000, 1000, 120, 180);
        assertFalse(bottom.update(800, 690, 10));
    }
    @Test public void amountAndLevelsAreClamped() {
        VideoSwipePolicy g = new VideoSwipePolicy();
        g.begin(400, 500, 1000, 1000, 100, 100);
        assertTrue(g.update(400, 400, 10));
        assertEquals(1f, g.amount(-10000), 0.001f);
        assertEquals(-1f, g.amount(10000), 0.001f);
        assertEquals(0f, VideoSwipePolicy.clamp(-4), 0.001f);
        assertEquals(1f, VideoSwipePolicy.clamp(3), 0.001f);
    }

    @Test public void upwardVolumeWorksFromLowerScreenWithControlsHidden() {
        VideoSwipePolicy swipe=new VideoSwipePolicy();
        float bottom=VideoSwipePolicy.bottomProtectionPx(680f,145f,false);
        assertEquals(51f,bottom,0.001f);
        swipe.begin(1300,595,1536,680,
                VideoSwipePolicy.topProtectionPx(680f,80f,false),bottom);
        assertTrue(swipe.update(1300,480,12));
        assertEquals(VideoSwipePolicy.VOLUME,swipe.side());
        assertTrue(swipe.amount(480)>0f);
    }

    @Test public void seekBarRemainsProtectedWhenControlsShown() {
        float bottom=VideoSwipePolicy.bottomProtectionPx(680f,145f,true);
        assertTrue(bottom>100f);
        VideoSwipePolicy swipe=new VideoSwipePolicy();
        swipe.begin(1300,595,1536,680,
                VideoSwipePolicy.topProtectionPx(680f,80f,true),bottom);
        assertFalse(swipe.update(1300,480,12));
        assertEquals(VideoSwipePolicy.NONE,swipe.side());
    }

    @Test public void downwardVolumeAndLeftBrightnessWorkWithControlsHidden() {
        float guard=VideoSwipePolicy.bottomProtectionPx(680f,145f,false);
        VideoSwipePolicy volume=new VideoSwipePolicy();
        volume.begin(1400,220,1536,680,68,guard);
        assertTrue(volume.update(1400,330,12));
        assertTrue(volume.amount(330)<0f);
        VideoSwipePolicy brightness=new VideoSwipePolicy();
        brightness.begin(90,595,1536,680,68,guard);
        assertTrue(brightness.update(90,450,12));
        assertEquals(VideoSwipePolicy.BRIGHTNESS,brightness.side());
    }

    @Test public void bottomSystemNavigationZoneRemainsProtected() {
        VideoSwipePolicy swipe=new VideoSwipePolicy();
        swipe.begin(1400,665,1536,680,68,
                VideoSwipePolicy.bottomProtectionPx(680f,145f,false));
        assertFalse(swipe.update(1400,500,12));
    }
}
