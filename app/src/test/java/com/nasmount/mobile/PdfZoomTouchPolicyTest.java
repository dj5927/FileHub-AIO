package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfZoomTouchPolicyTest {
    @Test public void zoomedPdfGetsUnscaledTouchAreas(){
        assertEquals(50,PdfZoomTouchPolicy.EDGE_WIDTH_DP);
        assertTrue(PdfZoomTouchPolicy.showFixedEdges(true,125));
        assertTrue(PdfZoomTouchPolicy.showFixedEdges(true,250));
        assertFalse(PdfZoomTouchPolicy.showFixedEdges(true,100));
        assertFalse(PdfZoomTouchPolicy.showFixedEdges(true,75));
    }
    @Test public void epubAlwaysKeepsItsOriginalGestures(){
        assertFalse(PdfZoomTouchPolicy.showFixedEdges(false,100));
        assertFalse(PdfZoomTouchPolicy.showFixedEdges(false,250));
    }
}
