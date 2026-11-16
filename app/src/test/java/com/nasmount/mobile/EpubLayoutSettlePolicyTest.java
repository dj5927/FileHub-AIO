package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubLayoutSettlePolicyTest {
    @Test public void backwardCrossChapterWaitsForLateFontReflow(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        int w=1100;
        for(int time=0;time<=900;time+=100)
            assertFalse("early commit "+time,p.observe(12*w,w,time,true));
        // Simulate a Fold chapter growing from 13 to 15 pages as fonts settle.
        for(int time=1000;time<=1300;time+=100)
            assertFalse("reflow "+time,p.observe(14*w,w,time,true));
        assertTrue(p.observe(14*w,w,1400,true));
        assertEquals(14*w,p.lastExtent());
        assertEquals(w,p.viewport());
    }
    @Test public void viewportChangeResetsStability(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<=400;time+=100)
            p.observe(10*500,500,time,true);
        assertFalse(p.observe(8*720,720,1000,true));
        assertFalse(p.observe(8*720,720,1100,true));
        assertFalse(p.observe(8*720,720,1200,true));
        assertFalse(p.observe(8*720,720,1300,true));
        assertTrue(p.observe(8*720,720,1400,true));
    }
    @Test public void fastForwardChapterIsNotHeldAsLong(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<500;time+=100)
            assertFalse(p.observe(7200,600,time,false));
        assertTrue(p.observe(7200,600,500,false));
    }
    @Test public void capsWaitForBooksThatKeepReflowing(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<3500;time+=100)
            assertFalse(p.observe((time/100)*40,650,time,true));
        assertTrue(p.observe(1400,650,3500,true));
    }
}
