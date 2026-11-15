package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class ReaderHistoryTest {
    @Test public void sameBookHasStableScopedIdentity(){
        String local=ReaderHistory.id("local",null,"/storage/0/Books/a.zip",null);
        assertEquals(local,ReaderHistory.id("local",null,"/storage/0/Books/a.zip",null));
        assertNotEquals(local,ReaderHistory.id("remote","nas1","/Books/a.zip",null));
        assertNotEquals(ReaderHistory.id("remote","nas1","/Books/a.zip",null),
                ReaderHistory.id("remote","nas2","/Books/a.zip",null));
        assertNotEquals(ReaderHistory.id("saf",null,null,"content://a"),
                ReaderHistory.id("saf",null,null,"content://b"));
    }
    @Test public void bookmarksDecodeSortedUniqueAndSafe(){
        assertEquals(Arrays.asList(2,5,12),ReaderHistory.decode("[12,2,5,2]"));
        assertTrue(ReaderHistory.decode("not json").isEmpty());
    }
    @Test public void progressIsClampedAndBookCompletesAtHundred(){
        assertEquals(0,ReaderHistory.percentFromUnits(0,56));
        assertEquals(77,ReaderHistory.percentFromUnits(43,56));
        assertEquals(100,ReaderHistory.percentFromUnits(56,56));
        assertEquals(100,ReaderHistory.percentFromUnits(200,56));
        assertEquals(0,ReaderHistory.percentFromUnits(-4,56));
        assertEquals(0,ReaderHistory.percentFromUnits(20,0));
    }
}
