package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReaderQuickSeekTest {
    @Test public void comicPageEndpointsAndHundredPages(){
        assertEquals(0,ReaderQuickSeek.toPage(0,100));
        assertEquals(99,ReaderQuickSeek.toPage(1000,100));
        assertEquals(50,ReaderQuickSeek.toPage(505,100));
        assertEquals(1000,ReaderQuickSeek.fromPage(99,100));
    }
    @Test public void largeTxtOffsetsAreOverflowSafe(){
        long length=25_000_000_000L;
        assertEquals(0L,ReaderQuickSeek.toOffset(0,length));
        assertEquals(length,ReaderQuickSeek.toOffset(1000,length));
        assertEquals(length/2,ReaderQuickSeek.toOffset(500,length));
        assertEquals(500,ReaderQuickSeek.fromOffset(length/2,length));
    }
    @Test public void smallBooksAndInvalidLimitsAreSafe(){
        assertEquals(0,ReaderQuickSeek.toPage(900,0));
        assertEquals(0,ReaderQuickSeek.toPage(900,1));
        assertEquals(0,ReaderQuickSeek.fromPage(9,1));
        assertEquals(0,ReaderQuickSeek.fromOffset(30,0));
        assertEquals(0,ReaderQuickSeek.toOffset(100,-20));
        assertEquals(0L,ReaderQuickSeek.toOffset(-50,100));
    }
    @Test public void pageSeekNeverMovesBackward(){
        int last=-1;
        for(int i=0;i<=1000;i++){
            int page=ReaderQuickSeek.toPage(i,1013);
            assertTrue(page>=last);
            last=page;
        }
    }
}
