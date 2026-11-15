package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OtaNoteSummaryTest {
    @Test public void onlyShortUserRelevantSectionsAreShown(){
        String raw="versionCode: 89\n\n"+
                "## 추가된 기능\n- LAN QR 카메라 스캔\n"+
                "## 수정 사항\n- 책 목록 렉 수정\n"+
                "## 기타 사항\n- 기존 설정 유지\n"+
                "SHA-256: ABC\n\n"+
                "## 자동 테스트\n- 476 tests passed\n";
        assertEquals("추가된 기능\n• LAN QR 카메라 스캔\n\n"+
                "수정 사항\n• 책 목록 렉 수정\n\n"+
                "기타 사항\n• 기존 설정 유지",OtaNoteSummary.format(raw));
    }
    @Test public void oldLongReleaseNotesDoNotFloodDialog(){
        String legacy="versionCode: 88\n\n"+
                "FileHub AIO Android and TV\n- developer information\n"+
                "SHA-256: xxx\n200 lines";
        assertEquals("새 버전이 준비됐습니다.",OtaNoteSummary.format(legacy));
    }
    @Test public void bulletsAreCappedAndLongLinesTrimmed(){
        String notes="## 추가된 기능\n";
        for(int i=0;i<15;i++)notes+="- abc"+i+"\n";
        String shortText=OtaNoteSummary.format(notes);
        assertTrue(shortText.contains("abc3"));
        assertFalse(shortText.contains("abc4"));
    }
}
