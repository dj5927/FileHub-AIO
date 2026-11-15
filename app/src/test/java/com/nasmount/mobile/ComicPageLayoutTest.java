package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ComicPageLayoutTest {
    @Test public void portraitIsUntouchedAndLandscapeCreatesTwoPages(){
        List<ComicPageLayout.Page> pages=ComicPageLayout.create(Arrays.asList(
                new int[]{800,1200},new int[]{2100,1100},new int[]{900,1400}),
                ComicPageLayout.AUTO,false);
        assertEquals(4,pages.size());
        assertEquals(ComicPageLayout.WHOLE,pages.get(0).slice);
        assertEquals(ComicPageLayout.LEFT,pages.get(1).slice);
        assertEquals(ComicPageLayout.RIGHT,pages.get(2).slice);
        assertTrue(ComicPageLayout.twoHalvesTogether(pages,1));
        assertEquals(1,ComicPageLayout.firstPageOfSource(pages,1));
    }
    @Test public void japaneseOrderReadsRightHalfFirst(){
        List<ComicPageLayout.Page> pages=ComicPageLayout.create(
                Collections.singletonList(new int[]{2100,1000}),0,true);
        assertEquals(ComicPageLayout.RIGHT,pages.get(0).slice);
        assertEquals(ComicPageLayout.LEFT,pages.get(1).slice);
    }
    @Test public void wideSpreadsRespectTwoHalvesNotOddAdjacentPages(){
        List<ComicPageLayout.Page> logical=ComicPageLayout.create(Arrays.asList(
                new int[]{800,1300},new int[]{2200,1100},
                new int[]{800,1300},new int[]{800,1300}),
                ComicPageLayout.AUTO,false);
        List<Integer> starts=ComicPageLayout.spreadStarts(logical);
        assertEquals(Arrays.asList(0,1,3),starts);
        assertEquals(1,ComicPageLayout.spreadStart(starts,2));
        assertEquals(3,ComicPageLayout.spreadEnd(starts,1,logical.size()));
    }
    @Test public void disableAndThresholdWork(){
        assertFalse(ComicPageLayout.shouldSplit(1700,1200,ComicPageLayout.NEVER));
        assertTrue(ComicPageLayout.shouldSplit(1700,1200,ComicPageLayout.AUTO));
        assertFalse(ComicPageLayout.shouldSplit(1250,1200,ComicPageLayout.AUTO));
        assertTrue(ComicPageLayout.shouldSplit(1400,1200,ComicPageLayout.AGGRESSIVE));
    }
}
