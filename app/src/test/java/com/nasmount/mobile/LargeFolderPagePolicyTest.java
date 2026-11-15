package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class LargeFolderPagePolicyTest {
    @Test public void hugeBookLibraryDoesNotRenderThousandsAtOnce(){
        int total=100_000;
        int start=0, batches=0;
        while(start<total){
            int end=LargeFolderPagePolicy.end(total,start);
            assertTrue(end>start);
            assertTrue(end-start<=LargeFolderPagePolicy.ROWS_PER_PAGE);
            start=end;batches++;
        }
        assertEquals(total,start);
        assertEquals(1667,batches);
    }
    @Test public void lastPageAndInvalidOffsets(){
        assertEquals(0,LargeFolderPagePolicy.end(0,0));
        assertEquals(1,LargeFolderPagePolicy.end(1,0));
        assertEquals(64,LargeFolderPagePolicy.end(64,60));
        assertThrows(IllegalArgumentException.class,
                ()->LargeFolderPagePolicy.end(10,11));
    }
}
