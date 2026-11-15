package com.nasmount.mobile;

import org.junit.Test;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class SamiSubtitleConverterTest {
    private static final String SAMPLE =
            "<SAMI><HEAD><STYLE>.KRCC {lang:ko-KR;}</STYLE></HEAD><BODY>" +
            "<SYNC Start=-1000><P Class=KRCC><Font color=#AAFF00>안녕하세요</Font><BR>반갑습니다" +
            "<SYNC Start=2000><P Class=KRCC>&nbsp;" +
            "<SYNC Start=3100><P Class=KRCC>두 번째 &amp; 세 번째" +
            "<SYNC Start=7100><P Class=KRCC>&nbsp;</BODY></SAMI>";

    @Test public void convertsKoreanAndHtmlLineBreaksIntoSrtTiming() {
        String output = SamiSubtitleConverter.toSrtText(SAMPLE);
        assertTrue(output.contains("00:00:00,000 --> 00:00:02,000"));
        assertTrue(output.contains("안녕하세요\n반갑습니다"));
        assertTrue(output.contains("00:00:03,100 --> 00:00:07,100"));
        assertTrue(output.contains("두 번째 & 세 번째"));
        assertFalse(output.contains("<Font"));
    }

    @Test public void ignoresBlankSamiCuesAndKeepsCueNumbersConsecutive() {
        String output = SamiSubtitleConverter.toSrtText(SAMPLE);
        assertTrue(output.startsWith("1\n"));
        assertTrue(output.contains("\n\n2\n"));
        assertFalse(output.contains("\n\n3\n"));
        assertFalse(output.contains("&nbsp;"));
    }

    @Test public void choosesKoreanWhenSamiHasSeveralCaptionLanguages() {
        String input = "<SAMI><BODY>" +
                "<SYNC Start=500><P Class=ENCC>Hello<P Class=KRCC>안녕" +
                "<SYNC Start=1500><P Class=ENCC>&nbsp;<P Class=KRCC>&nbsp;" +
                "</BODY></SAMI>";
        String output = SamiSubtitleConverter.toSrtText(input);
        assertTrue(output.contains("안녕"));
        assertFalse(output.contains("Hello"));
    }

    @Test public void decodesLegacyCp949WithoutBrokenKorean() {
        byte[] input = SAMPLE.getBytes(Charset.forName("MS949"));
        String output = SamiSubtitleConverter.toSrt(input);
        assertTrue(output.contains("안녕하세요"));
        assertTrue(output.contains("반갑습니다"));
        assertFalse(output.contains("\uFFFD"));
    }

    @Test public void handlesUtf8AndUtf8Bom() {
        byte[] bytes = SAMPLE.getBytes(StandardCharsets.UTF_8);
        assertTrue(SamiSubtitleConverter.toSrt(bytes).contains("두 번째"));
        byte[] bom = new byte[bytes.length + 3];
        bom[0] = (byte)0xEF;
        bom[1] = (byte)0xBB;
        bom[2] = (byte)0xBF;
        System.arraycopy(bytes, 0, bom, 3, bytes.length);
        assertTrue(SamiSubtitleConverter.toSrt(bom).contains("안녕하세요"));
    }

    @Test public void rejectsPlainTextWithoutSamiMarkers() {
        try {
            SamiSubtitleConverter.toSrtText("Not a SAMI file");
            fail("Expected invalid SAMI format");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("SMI"));
        }
    }

    @Test public void handlesNumericHtmlEntities() {
        String input = "<SAMI><BODY><SYNC Start=1000><P Class=KRCC>" +
                "가&#xAC00; &#33; &#160;" +
                "<SYNC Start=2400><P Class=KRCC>&nbsp;</BODY></SAMI>";
        String output = SamiSubtitleConverter.toSrtText(input);
        assertTrue(output.contains("가가 !"));
    }
}
