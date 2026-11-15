package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubProgressMapTest {
    @Test public void chapterIndexIsNotReadingPercentage(){
        // Tiny intro; long first chapter; short remaining chapters.
        EpubProgressMap map=new EpubProgressMap(new long[]{10,900,20,70});
        assertEquals(1,map.percentAt(10000));
        assertEquals(46,map.percentAt(10000+5000));
        assertEquals(91,map.percentAt(20000));
        assertEquals(100,map.percentAt(39999));
    }
    @Test public void percentSeekFindsCorrectChapterAndFraction(){
        EpubProgressMap map=new EpubProgressMap(new long[]{100,900});
        assertEquals(0,map.positionAt(0));
        assertEquals(10000,map.positionAt(100));
        assertEquals(14999,map.positionAt(550));
        assertEquals(19999,map.positionAt(1000));
        assertEquals(1,map.chapterAt(800));
    }
    @Test public void seekProgressIsMonotonicAcrossAllPositions(){
        EpubProgressMap map=new EpubProgressMap(
                new long[]{1,7500,1,10,50000,20,1200});
        int previous=-1;
        for(int p=0;p<=1000;p++){
            int position=map.positionAt(p);
            assertTrue("Seek must never go backward at "+p,
                    position>=previous);
            previous=position;
        }
        assertEquals(0,map.permilleAt(0));
        assertEquals(1000,map.permilleAt(69999));
    }
    @Test public void emptyChaptersHaveMinimumWeightAndAllInputsClamp(){
        EpubProgressMap map=new EpubProgressMap(new long[]{0,-1,998});
        assertEquals(1000,map.totalWeight());
        assertEquals(0,map.positionAt(-100));
        assertEquals(29999,map.positionAt(5000));
        assertEquals(0,map.percentAt(-100));
        assertEquals(100,map.percentAt(29999));
    }
    @Test public void legacyPositionEncodingAndBookmarksRemainUnchanged(){
        EpubProgressMap map=new EpubProgressMap(new long[]{2,200,400});
        int oldBookmarkedPosition=2*10000+4100;
        assertTrue(map.percentAt(oldBookmarkedPosition)>33);
        assertEquals(2,oldBookmarkedPosition/10000);
        assertEquals(4100,oldBookmarkedPosition%10000);
    }
}
