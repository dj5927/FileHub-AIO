package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FileHubBrandLayoutTest {
    @Test public void drawerAndMainToolbarShareExactlySameHeight(){
        assertEquals(65,FileHubBrandLayout.HEADER_HEIGHT_DP);
        assertEquals(40,FileHubBrandLayout.LEADING_SLOT_WIDTH_DP);
    }
    @Test public void brandWordmarkInkCenteredOnHamburgerOnHomeAndDrawer(){
        // Alpha bbox of 368x66 transparent-padded wordmark is y=10..56.
        // FIT_CENTER must position the visible glyphs exactly on y=32.5dp.
        assertEquals(32.5f,FileHubBrandLayout.visibleImageCenterDp(
                90,30,368,66,10,56),0.01f);
        assertEquals(32.5f,FileHubBrandLayout.visibleImageCenterDp(
                87,22,368,66,10,56),0.01f);
    }
    @Test public void drawerIconAndWordmarkShareVisibleCenter(){
        // Icon bbox: y=20..132 of 148x148 PNG on centered 30x33 view.
        // It differs from the exact title center by <0.5dp.
        float iconCenter=FileHubBrandLayout.visibleImageCenterDp(
                30,33,148,148,20,132);
        float wordmarkCenter=FileHubBrandLayout.visibleImageCenterDp(
                87,22,368,66,10,56);
        assertTrue(Math.abs(iconCenter-wordmarkCenter)<0.5f);
    }
    @Test public void narrowAndUnfoldedFoldWordmarksDoNotOverflow(){
        assertTrue(FileHubBrandLayout.wordmarkWidthDp(320)<=320-106);
        assertEquals(90,FileHubBrandLayout.wordmarkWidthDp(320));
        assertEquals(90,FileHubBrandLayout.wordmarkWidthDp(900));
        assertEquals(87,FileHubBrandLayout.drawerWordmarkWidthDp(275));
        assertEquals(87,FileHubBrandLayout.drawerWordmarkWidthDp(320));
        assertTrue(FileHubBrandLayout.drawerWordmarkWidthDp(275)<=275-60);
        assertTrue(FileHubBrandLayout.drawerWordmarkWidthDp(320)<=320-60);
    }
    @Test public void drawerDoesNotCoverStatusOrNavBars(){
        assertEquals(74,FileHubBrandLayout.drawerTopPx(74));
        assertEquals(60,FileHubBrandLayout.drawerBottomPx(48,60));
        assertEquals(48,FileHubBrandLayout.drawerBottomPx(48,30));
    }
}
