package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FoldPlayerLayoutPolicyTest {
    @Test public void foldedPhoneRemainsCompact() {
        assertFalse(FoldPlayerLayoutPolicy.isExpanded(375, 375, 840));
        assertFalse(FoldPlayerLayoutPolicy.isExpanded(390, 820, 390));
        assertFalse(FoldPlayerLayoutPolicy.isExpanded(620, 360, 800));
        assertEquals(3, FoldPlayerLayoutPolicy.fileGridColumns(false, 390));
    }

    @Test public void unfoldedFoldAndTabletsUseExpandedInterface() {
        assertTrue(FoldPlayerLayoutPolicy.isExpanded(600, 700, 950));
        assertTrue(FoldPlayerLayoutPolicy.isExpanded(550, 720, 600));
        assertEquals(4, FoldPlayerLayoutPolicy.fileGridColumns(true, 700));
        assertEquals(5, FoldPlayerLayoutPolicy.fileGridColumns(true, 850));
        assertEquals(6, FoldPlayerLayoutPolicy.fileGridColumns(true, 1200));
    }

    @Test public void contentAndDrawerNeverGrowAcrossWholeTablet() {
        assertEquals(1080, FoldPlayerLayoutPolicy.contentWidthDp(true, 1440));
        assertEquals(712, FoldPlayerLayoutPolicy.contentWidthDp(true, 760));
        assertEquals(360, FoldPlayerLayoutPolicy.drawerWidthDp(true, 760));
        assertEquals(312, FoldPlayerLayoutPolicy.drawerWidthDp(false, 380));
    }

    @Test public void expandedModeUsesBiggerButBoundedControls() {
        assertEquals(83, FoldPlayerLayoutPolicy.homeTileDp(false));
        assertEquals(105, FoldPlayerLayoutPolicy.homeTileDp(true));
        assertEquals(55, FoldPlayerLayoutPolicy.railControlDp(true, 740));
        assertEquals(48, FoldPlayerLayoutPolicy.railControlDp(true, 460));
    }
}
