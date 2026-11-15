package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BookPageSafetyTest {
    @Test public void reservesWholeLineOnFoldRightAndLeft(){
        assertTrue(BookPageSafety.usableHeight(1800,47,1.55f,12)<1730);
        assertEquals(BookPageSafety.usableHeight(1800,47,1.55f,12),
                BookPageSafety.usableHeight(1800,47,1.55f,12));
    }
    @Test public void largerFontOrSpacingReducesRowsPerPage(){
        int base=BookPageSafety.usableHeight(1900,40,1f,8);
        int large=BookPageSafety.usableHeight(1900,62,1.45f,8);
        assertTrue(large<base);
    }
    @Test public void tinyWindowHasSafeNonnegativeResult(){
        assertEquals(0,BookPageSafety.usableHeight(0,50,1f,8));
        assertTrue(BookPageSafety.usableHeight(70,50,2f,8)>0);
        assertTrue(BookPageSafety.usableHeight(70,50,2f,8)<=70);
    }
    @Test public void noUnexpectedOverflowForVeryLargeFont(){
        int usable=BookPageSafety.usableHeight(600,210,1.8f,25);
        assertTrue(usable>=32&&usable<=600);
    }
    @Test public void reservesMoreThanTwoLinesForKoreanFoldText(){
        int usable=BookPageSafety.usableHeight(1500,44,1.45f,12);
        assertTrue(1500-usable>2*44*1.45f);
    }
}
