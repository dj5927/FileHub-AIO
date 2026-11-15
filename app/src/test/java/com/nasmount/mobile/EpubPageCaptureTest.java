package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubPageCaptureTest {
    @Test public void foldedScreenshotUsesNativeSize(){
        assertEquals(360,EpubPageCapture.targetWidth(360,800));
        assertEquals(800,EpubPageCapture.targetHeight(360,800));
    }
    @Test public void unfoldedScreenshotNeverExceedsMemoryBudget(){
        int width=EpubPageCapture.targetWidth(2200,2400);
        int height=EpubPageCapture.targetHeight(2200,2400);
        assertTrue(width<=1200);
        assertTrue(height<=1900);
        assertTrue((long)width*height*4<10_000_000L);
    }
    @Test public void noCaptureForEmptyView(){
        assertEquals(0,EpubPageCapture.targetWidth(0,100));
        assertEquals(0,EpubPageCapture.targetHeight(700,0));
    }
}
