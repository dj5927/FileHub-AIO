package com.nasmount.mobile;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class BookSeriesQueueTest {
    @Test public void naturalVolumeOrderAndLastVolume()throws Exception{
        String json=BookSeriesQueue.encode(Arrays.asList(
                new BookSeriesQueue.Volume("BTOOOM 10.zip","/m/10.zip",null),
                new BookSeriesQueue.Volume("BTOOOM 02.zip","/m/02.zip",null),
                new BookSeriesQueue.Volume("BTOOOM 01.zip","/m/01.zip",null)));
        List<BookSeriesQueue.Volume> books=BookSeriesQueue.decode(json);
        assertEquals("BTOOOM 01.zip",books.get(0).name);
        assertEquals("BTOOOM 02.zip",BookSeriesQueue.next(books,
                "BTOOOM 01.zip","/m/01.zip").name);
        assertNull(BookSeriesQueue.next(books,"BTOOOM 10.zip","/m/10.zip"));
        assertNull(BookSeriesQueue.next(books,"BTOOOM 02.zip","/wrong/location"));
    }
    @Test public void neverAutoContinueIntoUnrelatedComicSeries(){
        assertTrue(BookSeriesQueue.sameSeries("BTOOOM 01.zip","BTOOOM 02.zip"));
        assertTrue(BookSeriesQueue.sameSeries("Code No9 01권.cbz","Code No9 02권.rar"));
        assertFalse(BookSeriesQueue.sameSeries("BTOOOM 01.zip","Code No9 01.zip"));
    }
    @Test public void survivesSafUriWithSpecialChars()throws Exception{
        List<BookSeriesQueue.Volume> out=BookSeriesQueue.decode(
                BookSeriesQueue.encode(Collections.singletonList(
                    new BookSeriesQueue.Volume("한글 01.cbz",null,
                            "content://tree/document%3A1"))));
        assertEquals("content://tree/document%3A1",out.get(0).location());
    }
}
