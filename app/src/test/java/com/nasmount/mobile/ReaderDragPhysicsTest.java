package com.nasmount.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ReaderDragPhysicsTest {
    @Test public void fingerMovementSetsCurlDirectlyAndReverses(){
        assertEquals(0f,ReaderDragPhysics.progress(950,950,1000,1),.0001f);
        assertTrue(ReaderDragPhysics.progress(950,550,1000,1)>.40f);
        assertEquals(0f,ReaderDragPhysics.progress(950,950,1000,1),.0001f);
    }
    @Test public void japaneseDirectionMirrorsMotion(){
        assertEquals(ReaderDragPhysics.progress(900,470,1000,1),
                ReaderDragPhysics.progress(100,530,1000,-1),.0001f);
        assertEquals(0f,ReaderDragPhysics.progress(100,50,1000,-1),.0001f);
    }
    @Test public void diagonalCornerLiftAndCancel(){
        assertTrue(ReaderDragPhysics.diagonal(800,400,1000)<0);
        assertTrue(ReaderDragPhysics.diagonal(400,800,1000)>0);
        assertFalse(ReaderDragPhysics.shouldCommit(.42f));
        assertTrue(ReaderDragPhysics.shouldCommit(.44f));
    }
    @Test public void dragMustExceedSlopAndHaveHorizontalComponent(){
        assertFalse(ReaderDragPhysics.shouldStart(3,4,15));
        assertFalse(ReaderDragPhysics.shouldStart(10,60,15));
        assertTrue(ReaderDragPhysics.shouldStart(45,-55,15));
    }
}
