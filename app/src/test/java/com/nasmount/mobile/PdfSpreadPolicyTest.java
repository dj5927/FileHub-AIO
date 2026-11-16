package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfSpreadPolicyTest {
    @Test public void foldedUsesSinglePageExpandedUsesTwo(){
        assertFalse(PdfSpreadPolicy.active(true,420));
        assertTrue(PdfSpreadPolicy.active(true,650));
        assertFalse(PdfSpreadPolicy.active(false,800));
    }
    @Test public void twoPageSpreadsKeepAdjacentPagesTogether(){
        assertEquals(0,PdfSpreadPolicy.start(1,379,true));
        assertEquals(2,PdfSpreadPolicy.start(2,379,true));
        assertEquals(2,PdfSpreadPolicy.start(3,379,true));
        assertEquals(4,PdfSpreadPolicy.next(2,1,379,true));
        assertEquals(0,PdfSpreadPolicy.next(2,-1,379,true));
        assertEquals(378,PdfSpreadPolicy.start(378,379,true));
        assertEquals(379,PdfSpreadPolicy.lastVisible(378,379,true));
        assertEquals("1–2 / 379쪽",PdfSpreadPolicy.pageLabel(0,379,true));
        assertEquals("379 / 379쪽",PdfSpreadPolicy.pageLabel(378,379,true));
    }
    @Test public void singlePageStillMovesByOne(){
        assertEquals(1,PdfSpreadPolicy.next(0,1,379,false));
        assertEquals(7,PdfSpreadPolicy.start(7,379,false));
        assertEquals("8 / 379쪽",PdfSpreadPolicy.pageLabel(7,379,false));
    }
}
