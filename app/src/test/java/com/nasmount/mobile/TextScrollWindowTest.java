package com.nasmount.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class TextScrollWindowTest {
    @Test public void multiMegabyteTextUsesSmallWindow(){
        String longText="가나다라마바사".repeat(300000);
        int start=TextScrollWindow.start(longText,600000);
        int end=TextScrollWindow.end(longText,start);
        assertTrue(end-start<=TextScrollWindow.SPAN);
        assertTrue(start<=600000 && end>600000);
        assertTrue(TextScrollWindow.shift(longText,start,end,
                start+11000,end-100));
    }
    @Test public void pageWindowsKeepSurrogatePairs(){
        String text="x".repeat(24000)+"😀"+"y".repeat(6000);
        int end=TextScrollWindow.end(text,0);
        assertFalse(Character.isHighSurrogate(text.charAt(end-1)));
        assertEquals(0,TextScrollWindow.start(text,5));
    }
}
