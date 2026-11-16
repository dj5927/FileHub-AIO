package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfEpubPageSourceTest {
    @Test public void pageAtFitSizeCentersWithoutCropping(){
        String html=PdfEpubPageSource.pageHtml(100);
        assertTrue(html.contains("align-items:center"));
        assertTrue(html.contains("justify-content:center"));
        assertTrue(html.contains("max-height:100dvh"));
        assertTrue(html.contains("pdf-current-page.png"));
    }

    @Test public void zoomedPageCanScrollWithoutTrimmingSource(){
        String html=PdfEpubPageSource.pageHtml(175);
        assertTrue(html.contains("width:175vw"));
        assertTrue(html.contains("overflow:auto"));
        assertFalse(html.contains("max-height:100dvh"));
        assertTrue(PdfEpubPageSource.pageHtml(300).contains("width:250vw"));
    }
    @Test public void foldSpreadShowsTwoPdfPages(){
        String html=PdfEpubPageSource.spreadHtml(100,true);
        assertTrue(html.contains("width:50vw"));
        assertTrue(html.contains("pdf-current-page.png"));
        assertTrue(html.contains("pdf-right-page.png"));
        assertTrue(html.contains("height:100vh"));
    }
    @Test public void finalOddFoldSpreadLeavesBlankRightSide(){
        String html=PdfEpubPageSource.spreadHtml(100,false);
        assertTrue(html.contains("pdf-current-page.png"));
        assertFalse(html.contains("pdf-right-page.png"));
    }
    @Test public void zoomedFoldSpreadCanScroll(){
        String html=PdfEpubPageSource.spreadHtml(175,true);
        assertTrue(html.contains("87.5vw"));
        assertTrue(html.contains("overflow:auto"));
    }
}
