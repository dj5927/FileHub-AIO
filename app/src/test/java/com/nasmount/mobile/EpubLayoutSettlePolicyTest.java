package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubLayoutSettlePolicyTest {
    @Test public void backwardCrossChapterDoesNotHave1250MsFixedPause(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        int w=1100;
        for(int time=0;time<300;time+=70)
            assertFalse("early commit "+time,p.observe(12*w,w,time,true));
        assertTrue(p.observe(12*w,w,350,true));
        assertEquals(12*w,p.lastExtent());
        assertEquals(w,p.viewport());
    }
    @Test public void viewportChangeResetsStability(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<=210;time+=70)
            p.observe(10*500,500,time,true);
        assertFalse(p.observe(8*720,720,280,true));
        assertFalse(p.observe(8*720,720,350,true));
        assertTrue(p.observe(8*720,720,420,true));
    }
    @Test public void fastForwardChapterIsNotHeldAsLong(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<160;time+=70)
            assertFalse(p.observe(7200,600,time,false));
        assertTrue(p.observe(7200,600,210,false));
    }
    @Test public void capsWaitForBooksThatKeepReflowing(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        for(int time=0;time<1800;time+=70)
            assertFalse(p.observe((time/70)*40,650,time,true));
        assertTrue(p.observe(1400,650,1820,true));
    }
    @Test public void lateFontReflowResetsShortWait(){
        EpubLayoutSettlePolicy p=EpubLayoutSettlePolicy.create();
        assertFalse(p.observe(12*1100,1100,0,true));
        assertFalse(p.observe(12*1100,1100,70,true));
        assertFalse(p.observe(14*1100,1100,280,true));
        assertFalse(p.observe(14*1100,1100,350,true));
        assertTrue(p.observe(14*1100,1100,420,true));
    }
}
