package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubTouchZonesTest {
    private int gesture(float startX,float startY,float endX,float endY,
                        long duration,boolean paged,boolean multi) {
        return EpubTouchZones.classify(startX,startY,endX,endY,1000,
                duration,18f,65f,paged,multi);
    }
    @Test public void singleTapsHaveThreeRegions() {
        assertEquals(EpubTouchZones.PREVIOUS,gesture(140,300,143,301,110,true,false));
        assertEquals(EpubTouchZones.NEXT,gesture(820,300,819,299,100,true,false));
        assertEquals(EpubTouchZones.TOGGLE_BARS,gesture(480,300,482,300,100,true,false));
    }
    @Test public void zonesStillWorkInScrollMode() {
        assertEquals(EpubTouchZones.PREVIOUS,gesture(140,300,140,300,110,false,false));
        assertEquals(EpubTouchZones.NEXT,gesture(860,300,860,300,110,false,false));
        assertEquals(EpubTouchZones.TOGGLE_BARS,gesture(500,300,500,300,110,false,false));
    }
    @Test public void horizontalSwipeIsDistinctFromTap() {
        assertEquals(EpubTouchZones.SWIPE_NEXT,gesture(760,300,290,304,270,true,false));
        assertEquals(EpubTouchZones.SWIPE_PREVIOUS,gesture(250,300,760,302,280,true,false));
        assertEquals(EpubTouchZones.NONE,gesture(650,100,650,500,350,true,false));
        assertEquals(EpubTouchZones.NONE,gesture(760,300,290,304,270,false,false));
    }
    @Test public void noAccidentalPageOnLongPressOrTwoFingers(){
        assertEquals(EpubTouchZones.NONE,gesture(100,100,100,100,1050,true,false));
        assertEquals(EpubTouchZones.NONE,gesture(100,100,100,100,150,true,true));
        assertEquals(EpubTouchZones.NONE,gesture(100,100,130,110,150,true,false));
    }
}
