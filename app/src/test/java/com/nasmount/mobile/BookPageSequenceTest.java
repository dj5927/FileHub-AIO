package com.nasmount.mobile;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Page boundaries always partition the original text without any gap. */
public final class BookPageSequenceTest {
    @Test public void everyPageStartsWherePreviousEnded(){
        String content="첫째 줄\n둘째 줄\n셋째 줄\n넷째 줄\n";
        List<Integer> pages=Arrays.asList(0,6,12,18);
        StringBuilder recovered=new StringBuilder();
        for(int i=0;i<pages.size();i++){
            int start=pages.get(i);
            int end=i+1<pages.size()?pages.get(i+1):content.length();
            recovered.append(content,start,end);
            assertEquals(i,BookPaginator.pageFor(pages,start));
        }
        assertEquals(content,recovered.toString());
    }
    @Test public void nextPageLookupIsStableAtExactBoundary(){
        List<Integer> starts=Arrays.asList(0,120,245,390);
        assertEquals(0,BookPaginator.pageFor(starts,119));
        assertEquals(1,BookPaginator.pageFor(starts,120));
        assertEquals(2,BookPaginator.pageFor(starts,245));
        assertEquals(3,BookPaginator.pageFor(starts,390));
    }
}
