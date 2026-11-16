package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class PipOverlaySizingTest {
    @Test public void foldedUsesFoldedPhysicalDisplay(){
        assertEquals(570,PipOverlaySizing.widthPx(658,44,1100));
    }
    @Test public void unfoldedStillHasReadableMaximum(){
        assertEquals(840,PipOverlaySizing.widthPx(2304,64,840));
    }
    @Test public void verySmallWindowNeverOverflows(){
        assertEquals(180,PipOverlaySizing.widthPx(220,20,600));
        assertEquals(1,PipOverlaySizing.widthPx(1,20,600));
    }
    @Test public void foldedThenUnfoldedRecomputesWidth(){
        assertEquals(578,PipOverlaySizing.widthPx(658,40,960));
        assertEquals(960,PipOverlaySizing.widthPx(2200,40,960));
    }
}
