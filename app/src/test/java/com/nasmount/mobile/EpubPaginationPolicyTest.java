package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubPaginationPolicyTest {
    @Test public void noBodyOrDocumentClippingAllowedInPagedMode(){
        for(boolean wide:new boolean[]{false,true}){
            String css=EpubPaginationPolicy.css(wide,18);
            assertTrue(css.contains("overflow-x:auto!important"));
            assertTrue(css.contains("overflow:visible!important"));
            assertFalse(css.contains("overflow:hidden"));
            assertTrue(css.contains("column-fill:auto!important"));
            assertTrue(css.contains("column-count:"+(wide?2:1)));
        }
    }
    @Test public void pageWidthsRemainExactWithPerPageTextMargins(){
        for(int margin:new int[]{8,18,30,50}){
            String css=EpubPaginationPolicy.css(false,margin);
            assertTrue(css.contains("column-gap:0!important"));
            assertTrue(css.contains("body > * {box-sizing:border-box!important"));
            assertTrue(css.contains("padding-left:"+margin+"px!important"));
            assertTrue(css.contains("padding-right:"+margin+"px!important"));
            assertTrue(css.contains("width:100vw!important"));
            assertTrue(css.contains("box-sizing:border-box!important"));
            assertFalse(css.contains("column-gap:"+(2*margin)+"px"));
        }
    }
    @Test public void finalPageForActualTwentyTwoPageChapterNeverSkips(){
        int width=658;
        int max=21*width; // 22 displayed pages, index 0..21
        assertEquals(22,EpubPaginationPolicy.pageCount(width,max));
        assertEquals(max,EpubPaginationPolicy.lastPageStart(width,max));
        assertEquals(21,EpubPaginationPolicy.pageIndex(max,width));
        assertEquals(20*width,EpubPaginationPolicy.target(max,-1,width,max));
        assertEquals(max,EpubPaginationPolicy.target(20*width,1,width,max));
        for(int index=0;index<=21;index++){
            int fraction=Math.round(index*9999f/21f);
            int storedPixel=Math.round(max*fraction/10000f);
            assertEquals("chapter page "+(index+1),index*width,
                    EpubPaginationPolicy.restoreNearest(storedPixel,width,max));
        }
        // Legacy 99.99% locations must reach last page, not previous page.
        assertEquals(max,EpubPaginationPolicy.restoreNearest(
                Math.round(max*.9999f),width,max));
    }
    @Test public void chromiumFinalColumnGapDoesNotHideRemainingText(){
        // Reproduced using the user's Dragon Raja EPUB on Chrome:
        // old: viewport=642, scrollMax=6402 (18px short of last boundary)
        // new CSS: viewport=642, scrollMax=6420 (exact 10th page).
        assertEquals(11,EpubPaginationPolicy.pageCount(642,6420));
        assertEquals(6420,EpubPaginationPolicy.target(9*642,1,642,6420));
        assertEquals(10,EpubPaginationPolicy.pageIndex(6402,642));
        assertEquals(6402,EpubPaginationPolicy.target(9*642,1,642,6402));
    }
    @Test public void longChapterDoesNotAdvanceAfterJustFirstPage(){
        int fullWidth=200*430;
        int max=EpubPaginationPolicy.maxScroll(fullWidth,430);
        int offset=0;
        int pages=1;
        while(EpubPaginationPolicy.hasPage(offset,1,430,max)){
            int next=EpubPaginationPolicy.target(offset,1,430,max);
            assertTrue(next>offset);
            offset=next;
            pages++;
            assertTrue(pages<=201);
        }
        assertEquals(200,pages);
        assertEquals(max,offset);
    }
    @Test public void ownStylesOverrideBookStylesAtEndOfHtmlHead(){
        String source="<html><head><style>body{overflow:hidden}</style>"+
                "</head><body><p>본문이 다음 장에서도 이어져야 합니다.</p></body></html>";
        String injected="<style id='nasmount'>body{overflow:visible!important}</style>";
        String result=EpubPaginationPolicy.injectAfterBookStyles(source,injected);
        assertTrue(result.indexOf("body{overflow:hidden}")<
                result.indexOf("body{overflow:visible!important}"));
        assertTrue(result.indexOf("nasmount")<result.indexOf("</head>"));
        assertEquals(1,result.split("nasmount",-1).length-1);
    }
    @Test public void cssInjectionWorksForBooksMissingHead(){
        String inserted=EpubPaginationPolicy.injectAfterBookStyles(
                "<html><body>전체 내용</body></html>","<style>TEST</style>");
        assertTrue(inserted.contains("<head><style>TEST</style></head><body>"));
        String plain=EpubPaginationPolicy.injectAfterBookStyles(
                "본문만 제공하는 EPUB","<style>TEST</style>");
        assertTrue(plain.startsWith("<!doctype html>"));
        assertTrue(plain.endsWith("</body></html>"));
    }
    @Test public void restoringNeverSkipsUnreadStartOfPage(){
        assertEquals(0,EpubPaginationPolicy.restoreTarget(299,400,10000));
        assertEquals(400,EpubPaginationPolicy.restoreTarget(799,400,10000));
        assertEquals(9600,EpubPaginationPolicy.restoreTarget(9999,400,10000));
        assertEquals(0,EpubPaginationPolicy.restoreTarget(-1,400,10000));
    }
    @Test public void previousAndNextAreReversibleAndNeverOutOfBounds(){
        int extent=8700;
        assertEquals(800,EpubPaginationPolicy.target(400,1,400,extent));
        assertEquals(400,EpubPaginationPolicy.target(800,-1,400,extent));
        assertEquals(0,EpubPaginationPolicy.target(0,-1,400,extent));
        assertEquals(8700,EpubPaginationPolicy.target(8700,1,400,extent));
        assertEquals(8700,EpubPaginationPolicy.target(8500,1,400,extent));
    }
}
