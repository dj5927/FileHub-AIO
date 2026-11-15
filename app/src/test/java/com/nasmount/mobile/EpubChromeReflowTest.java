package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpubChromeReflowTest {
    @Test public void snapsFullPagesAndKeepsNonAlignedFinalPageReachable(){
        for(int extent:new int[]{0,420,1080,6400,24000,360000}){
            for(int fraction=0;fraction<=9999;fraction+=111){
                int result=EpubChromeReflow.horizontalSnap(
                        fraction,extent,360);
                assertTrue(result>=0);
                assertTrue(result<=extent);
                // A rare half-page final spread (e.g. odd Fold column
                // count) is allowed only at the actual document END.
                assertTrue("fraction="+fraction+" extent="+extent,
                        result%360==0 || result==extent);
            }
        }
    }
    @Test public void foldAndPhoneReflowSnapToViewportWidth(){
        assertEquals(2100,EpubChromeReflow.horizontalSnap(4000,5600,700));
        assertEquals(2160,EpubChromeReflow.horizontalSnap(4000,5600,360));
        assertEquals(0,EpubChromeReflow.horizontalSnap(-1,5000,500));
    }
    @Test public void verticalReadingRetainsFraction(){
        assertEquals(1500,EpubChromeReflow.verticalRestore(5000,3000));
        assertEquals(0,EpubChromeReflow.verticalRestore(0,2000));
    }
    @Test public void toolbarHideUsesNearestFullPageWithoutRetreat(){
        int width=658,max=width*21;
        for(int index=0;index<=21;index++){
            int fraction=Math.round(9999f*index/21f);
            assertEquals(index*width,
                    EpubChromeReflow.horizontalSnap(fraction,max,width));
        }
    }
}
