package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfBookTypesTest {
    @Test public void pdfOpensCaseInsensitively(){
        assertTrue(PdfBookTypes.supports("문서.pdf"));
        assertTrue(PdfBookTypes.supports("책.PDF"));
        assertFalse(PdfBookTypes.supports("book.pdf.exe"));
        assertFalse(PdfBookTypes.supports("book.epub"));
        assertFalse(PdfBookTypes.supports(null));
    }
}
