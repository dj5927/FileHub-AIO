package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfPageSizingTest {
    @Test public void standardPageRespectsAspectAndMemory(){
        int[] size=PdfPageSizing.fit(612,792,1080,1f);
        assertEquals(1080,size[0]);
        assertEquals(1398,size[1],2);
    }
    @Test public void hugeAndNarrowPdfDoNotAllocateGiganticBitmap(){
        for(float ratio:new float[]{0.1f,1f,2f,10f,100f}){
            int[] size=PdfPageSizing.fit(500,500*ratio,3500,3f);
            assertTrue(size[0]>0 && size[1]>0);
            assertTrue(size[0]<=2400 && size[1]<=2400);
            assertTrue((long)size[0]*size[1]<=6_000_000L);
        }
    }
    @Test public void pageSelectionClampsToBoundaries(){
        assertEquals(0,PdfPageSizing.page(-100,205));
        assertEquals(204,PdfPageSizing.page(10000,205));
        assertEquals(5,PdfPageSizing.page(5,205));
    }
    @Test public void invalidDimensionsRejected(){
        assertThrows(IllegalArgumentException.class,()->
                PdfPageSizing.fit(0,600,400,1f));
    }
}
