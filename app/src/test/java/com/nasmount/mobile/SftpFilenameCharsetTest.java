package com.nasmount.mobile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class SftpFilenameCharsetTest {
    @Test public void sftpLegacyKoreanEncodingIsNotJustSavedUiText() {
        String file = "한글 파일.txt";
        Charset cp949 = SftpFilenameCharset.resolve(
                "CP949 / EUC-KR (한국어)");
        assertNotEquals(StandardCharsets.UTF_8, cp949);
        assertArrayEquals(file.getBytes(cp949),
                file.getBytes(SftpFilenameCharset.resolve("MS949")));
        assertEquals(file, new String(file.getBytes(cp949), cp949));
        assertNotEquals(file.getBytes(StandardCharsets.UTF_8).length,
                file.getBytes(cp949).length);
        assertEquals(StandardCharsets.UTF_8,
                SftpFilenameCharset.resolve("UTF-8"));
        assertNotNull(SftpFilenameCharset.resolve(
                "EUC-KR / CP51949 (한국어)"));
    }

    @Test public void sftpJapaneseAndChineseEncodingAreSelectable() {
        for (String name : new String[]{
                "Shift-JIS / CP932 (일본어)", "EUC-JP (일본어)",
                "GBK / CP936 (중국어 간체)",
                "GB18030 (중국어 간체)", "Big5 / CP950 (중국어 번체)"})
            assertNotNull(SftpFilenameCharset.resolve(name));
    }

    @Test public void invalidSftpCodepageCannotSilentlyBecomeUtf8() {
        assertThrows(IllegalArgumentException.class,
                () -> SftpFilenameCharset.resolve("nonexistent-codepage"));
        assertThrows(IllegalArgumentException.class,
                () -> SftpFilenameCharset.resolve("서버 기본"));
    }
}
