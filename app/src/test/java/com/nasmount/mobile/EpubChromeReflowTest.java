package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class EpubChromeReflowTest {
    @Test public void neverShowsTwoPartialNeighborPages(){
        for(int extent:new int[]{0,420,1080,6400,24000,360000}){
            for(int fraction=0;fraction<=9999;fraction+=111){
                int result=EpubChromeReflow.horizontalSnap(
                        fraction,extent,360);
                assertTrue(result>=0);
                assertTrue(result<=extent);
                assertEquals(0,result%360);
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
}
