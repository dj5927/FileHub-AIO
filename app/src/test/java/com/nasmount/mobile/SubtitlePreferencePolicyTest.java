package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SubtitlePreferencePolicyTest {
    @Test public void sizeClampsToValidBounds() {
        assertEquals(12, PlayerSettings.clampSubtitleSize(2));
        assertEquals(20, PlayerSettings.clampSubtitleSize(20));
        assertEquals(48, PlayerSettings.clampSubtitleSize(120));
    }
    @Test public void translatesCommonLanguages() {
        assertEquals("한국어", PlayerSettings.languageLabel("ko"));
        assertEquals("English", PlayerSettings.languageLabel("en"));
        assertEquals("日本語", PlayerSettings.languageLabel("ja"));
        assertEquals("한국어", PlayerSettings.languageLabel("ko-KR"));
        assertEquals("한국어", PlayerSettings.languageLabel("kor"));
        assertEquals("English", PlayerSettings.languageLabel("eng"));
        assertEquals("日本語", PlayerSettings.languageLabel("jpn"));
    }
    @Test public void unknownLanguageIsStillDisplayed() {
        assertEquals("언어 정보 없음", PlayerSettings.languageLabel(null));
        assertEquals("abc", PlayerSettings.languageLabel("abc"));
    }
}
