package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubReaderStatusTest {
    @Test public void noCenterTextDuringFirstOpen(){
        assertFalse(EpubReaderStatus.showCenterMessage(true,true,false));
    }
    @Test public void noCenterTextDuringPageOrChapterTurns(){
        assertFalse(EpubReaderStatus.showCenterMessage(false,true,false));
        assertFalse(EpubReaderStatus.showCenterMessage(false,false,false));
    }
    @Test public void actualReadErrorIsStillVisible(){
        assertTrue(EpubReaderStatus.showCenterMessage(false,false,true));
        assertTrue(EpubReaderStatus.showCenterMessage(true,true,true));
    }
}
