package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TextPageWindowTest {
    @Test public void startAndEndNeverGiveEmptyOrOutOfRangeWindow(){
        long total=3_100_000;
        for(long requested:new long[]{-50,0,1,1400,100000,3099999,3100000,
                Long.MAX_VALUE}){
            long position=TextPageWindow.point(requested,total);
            long base=TextPageWindow.base(requested,total,false);
            int span=TextPageWindow.span(base,total);
            assertTrue(position>=0&&position<total);
            assertTrue(base<=position&&base>=0);
            assertTrue(span>0&&span<=48000);
            assertTrue(base+span>position);
            assertTrue(base+span<=total);
        }
    }
    @Test public void windowShowsTextBeforeAndAfterResumedPage(){
        long total=1_000_000;
        long anchor=510_000;
        long start=TextPageWindow.base(anchor,total,false);
        assertEquals(504_000,start);
        assertEquals(48_000,TextPageWindow.span(start,total));
    }
    @Test public void nextWindowStartsAtExactPreviousEnd(){
        long end=48_000;
        assertEquals(end,TextPageWindow.base(end,1_000_000,true));
        assertEquals(48_000,TextPageWindow.span(end,1_000_000));
    }
    @Test public void hugeBookUsesLongArithmetic(){
        long length=25_000_000_000L;
        long at=24_999_999_999L;
        assertEquals(at,TextPageWindow.point(Long.MAX_VALUE,length));
        long base=TextPageWindow.base(Long.MAX_VALUE,length,false);
        assertEquals(6001,TextPageWindow.span(base,length));
    }
    @Test public void tinyBookCannotOverflowViewport(){
        assertEquals(1,TextPageWindow.span(0,1));
        assertEquals(0,TextPageWindow.span(0,0));
        assertEquals(0,TextPageWindow.point(1000,0));
    }
}
