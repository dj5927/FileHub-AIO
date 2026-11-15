package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class KoreanPageBoundaryTest {
    @Test public void koreanDialogueKeepsSentenceWithItsClosingQuote(){
        String s="그녀가 말했다. “정말 괜찮아요?” 그리고 그는 고개를 끄덕였다.";
        int visualEnd=s.indexOf("끄덕였다");
        int cutoff=KoreanPageBoundary.choose(s,0,visualEnd,
                s.indexOf("정말"));
        assertEquals("“정말 괜찮아요?” ".length()+s.indexOf('“'),
                cutoff);
        assertEquals("그리고 그는 고개를 ",s.substring(cutoff,visualEnd));
    }
    @Test public void hardParagraphBoundaryBeatsArbitraryCharacterCount(){
        String s="주인공은 책을 덮었다.\n새로운 장의 첫 문장이 시작된다.";
        int end=s.indexOf("시작된다");
        int point=KoreanPageBoundary.choose(s,0,end,9);
        assertEquals(s.indexOf('\n')+1,point);
        assertEquals("새로운 장의 첫 문장이 ",s.substring(point,end));
    }
    @Test public void noSentenceBreakMeansExactVisualLineCutoff(){
        String s="긴 문장이 아직 끝나지 않아 다음 페이지로 자연스럽게 이어진다";
        int limit=s.indexOf("자연스럽게");
        assertEquals(limit,KoreanPageBoundary.choose(s,0,limit,limit-6));
    }
    @Test public void punctuationInsideVersionOrAcronymIsNotSentence(){
        String s="버전 1.5를 쓰고 U.S. 표기도 그대로 둔다 뒤에 이어진다.";
        int end=s.indexOf("뒤에");
        assertEquals(end,KoreanPageBoundary.choose(s,0,end,0));
    }
    @Test public void everyCharacterIsStillPresentAfterBoundaryMove(){
        String s="이것은 첫 문장이다. 그 다음 문장은 길고 길어서 마지막 줄에서 끝나지 않았다.";
        int visualEnd=s.indexOf("마지막");
        int cut=KoreanPageBoundary.choose(s,0,visualEnd,0);
        assertTrue(cut>0&&cut<visualEnd);
        assertEquals(s,s.substring(0,cut)+s.substring(cut));
    }
    @Test public void punctuationRightAtPageEndPreservesMeasuredCut(){
        String s="가나다라. 마바사.";
        int pageEnd=s.indexOf("마바사");
        assertEquals(pageEnd,KoreanPageBoundary.choose(s,0,pageEnd,0));
    }
}
