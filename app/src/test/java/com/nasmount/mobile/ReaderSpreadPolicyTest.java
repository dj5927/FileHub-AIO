package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReaderSpreadPolicyTest {
    @Test public void foldedPhoneKeepsOnePage(){
        assertFalse(ReaderSpreadPolicy.comic(390,true,false));
        assertFalse(ReaderSpreadPolicy.text(390,true,0));
    }
    @Test public void unfoldedScreenShowsPairsOnlyInPageModes(){
        assertTrue(ReaderSpreadPolicy.comic(730,true,false));
        assertTrue(ReaderSpreadPolicy.text(730,true,0));
        assertFalse(ReaderSpreadPolicy.comic(730,true,true));
        assertFalse(ReaderSpreadPolicy.text(730,true,1));
        assertFalse(ReaderSpreadPolicy.text(730,false,0));
    }
    @Test public void oddLastPageAndRestoredProgressAreSafe(){
        assertEquals(42,ReaderSpreadPolicy.start(43,true));
        assertEquals(44,ReaderSpreadPolicy.end(42,56,true));
        assertEquals(55,ReaderSpreadPolicy.start(55,false));
        assertEquals(55,ReaderSpreadPolicy.end(54,55,true));
        assertEquals(0,ReaderSpreadPolicy.start(-2,true));
    }
}
