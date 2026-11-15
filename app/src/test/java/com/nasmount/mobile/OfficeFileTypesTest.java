package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class OfficeFileTypesTest {
    @Test public void excelAndHwpSupportedButUnknownFilesNotSentToOffice() {
        for (String type : new String[]{"report.XLS", "report.xlsx",
                "work.ods", "공문.hwp", "공문.HWPX"})
            assertTrue(OfficeFileTypes.supports(type));
        for (String type : new String[]{"movie.mkv", "a.rar", "a.md", "a.exe"})
            assertFalse(OfficeFileTypes.supports(type));
    }

    @Test public void mimeTypesAreNotPlainText() {
        assertEquals("application/vnd.ms-excel", OfficeFileTypes.mime("old.xls"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                OfficeFileTypes.mime("new.xlsx"));
        assertEquals("application/x-hwp", OfficeFileTypes.mime("a.hwp"));
        assertEquals("application/hwp+zip", OfficeFileTypes.mime("a.hwpx"));
    }

    @Test public void wordAndPowerPointOpenInInternalEditor() {
        assertTrue(OfficeFileTypes.supports("회사소개서.PPTX"));
        assertTrue(OfficeFileTypes.supports("보고서.DOCX"));
        assertEquals("slides", OfficeFileTypes.mode("회사소개서.pptx"));
        assertEquals("word", OfficeFileTypes.mode("보고서.docx"));
        assertEquals("hwp", OfficeFileTypes.mode("문서.hwpx"));
        assertEquals("sheet", OfficeFileTypes.mode("자료.xlsx"));
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                OfficeFileTypes.mime("회사소개서.PPTX"));
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                OfficeFileTypes.mime("보고서.DOCX"));
    }

    @Test public void legacyBinaryFormatsDoNotGoIntoZipReader() {
        assertEquals("legacy", OfficeFileTypes.mode("old.PPT"));
        assertEquals("legacy", OfficeFileTypes.mode("old.DOC"));
        assertEquals("unknown", OfficeFileTypes.mode("notes.txt"));
        assertEquals("unknown", OfficeFileTypes.mode(null));
        assertEquals("application/msword", OfficeFileTypes.mime("old.doc"));
        assertEquals("application/vnd.ms-powerpoint", OfficeFileTypes.mime("old.ppt"));
    }
}
