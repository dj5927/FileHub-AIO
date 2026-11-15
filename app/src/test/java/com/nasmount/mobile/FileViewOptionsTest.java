package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FileViewOptionsTest {
    @Test public void alwaysSortsDirectoriesFirstAndNameDescending() {
        assertTrue(FileViewOptions.compare(true,"b",0,0,false,"a",0,0,
                FileViewOptions.SIZE_LARGE) < 0);
        assertTrue(FileViewOptions.compare(false,"b",0,0,false,"a",0,0,
                FileViewOptions.NAME_DESC) < 0);
    }
    @Test public void sortingSizeAndDateUsesGivenNumericData() {
        assertTrue(FileViewOptions.compare(false,"A",200,10,false,"B",30,20,
                FileViewOptions.SIZE_LARGE) < 0);
        assertTrue(FileViewOptions.compare(false,"A",200,10,false,"B",30,20,
                FileViewOptions.DATE_NEW) > 0);
    }
    @Test public void hiddenPreferenceAlsoAccountsForDotFiles() {
        assertFalse(FileViewOptions.visible(".hidden",false,false));
        assertFalse(FileViewOptions.visible("system",true,false));
        assertTrue(FileViewOptions.visible(".hidden",false,true));
        assertTrue(FileViewOptions.visible("visible",false,false));
    }
    @Test public void searchMatchesCaseInsensitiveKoreanAndEnglish() {
        assertTrue(FileViewOptions.matches("방탄이 사진.JPG","사진"));
        assertTrue(FileViewOptions.matches("Document.PDF","docu"));
        assertFalse(FileViewOptions.matches("Picture.jpg","doc"));
    }
}
