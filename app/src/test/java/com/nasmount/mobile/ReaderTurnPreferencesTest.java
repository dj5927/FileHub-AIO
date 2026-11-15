package com.nasmount.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ReaderTurnPreferencesTest {
    @Test public void fourStylesRoundTripBetweenAllThreeReaderMenus(){
        for(int i=0;i<4;i++){
            int effect=ReaderTurnPreferences.fromIndex(i);
            assertEquals(i,ReaderTurnPreferences.toIndex(effect));
        }
    }
    @Test public void paperCurlIsFirstAndOldEffectsRetainTheirConstants(){
        assertEquals(EpubTurnGeometry.CURL,ReaderTurnPreferences.fromIndex(0));
        assertEquals(EpubTurnGeometry.NONE,ReaderTurnPreferences.fromIndex(3));
        assertEquals(1,ReaderTurnPreferences.toIndex(EpubTurnGeometry.SLIDE));
        assertEquals(2,ReaderTurnPreferences.toIndex(EpubTurnGeometry.FADE));
    }
}
