package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubTurnGeometryTest {
    @Test public void forwardCurlMovesRightCornerAcrossPage(){
        assertEquals(1000f,EpubTurnGeometry.bottomEdge(1000,0,true),.01f);
        assertEquals(0f,EpubTurnGeometry.bottomEdge(1000,1,true),.01f);
        assertTrue(EpubTurnGeometry.bottomEdge(1000,.5f,true)>0);
        assertTrue(EpubTurnGeometry.foldWidth(1000,.5f)>300f);
    }
    @Test public void backwardCurlPeelsFromLeftEdge(){
        assertEquals(0f,EpubTurnGeometry.topEdge(1000,0,false),.01f);
        assertEquals(1000f,EpubTurnGeometry.topEdge(1000,1,false),.01f);
        assertEquals(0f,EpubTurnGeometry.foldWidth(1000,0),.01f);
        assertEquals(0f,EpubTurnGeometry.foldWidth(1000,1),.01f);
    }
    @Test public void cornerLiftLagsBehindFingerPull(){
        float bottom=EpubTurnGeometry.bottomEdge(1000,.15f,true);
        float top=EpubTurnGeometry.topEdge(1000,.15f,true);
        assertTrue(top>bottom);
    }
    @Test public void effectsAndSpeedsHavePredictableDurations(){
        assertEquals(540,EpubTurnGeometry.duration(EpubTurnGeometry.CURL,false));
        assertTrue(EpubTurnGeometry.duration(EpubTurnGeometry.CURL,true)>540);
        assertTrue(EpubTurnGeometry.duration(EpubTurnGeometry.SLIDE,false)<
                EpubTurnGeometry.duration(EpubTurnGeometry.CURL,false));
        assertEquals(0,EpubTurnGeometry.duration(EpubTurnGeometry.NONE,false));
    }
}
