package com.nasmount.mobile;
import org.junit.Test;
import static org.junit.Assert.*;
public class FileIconTypeTest {
    @Test public void classifiesCaseInsensitiveAndUnknown(){
        String[][] rows={{"sample.XLSX","spreadsheet"},{"file.xls","spreadsheet"},{"a.PPTX","presentation"},
            {"a.docx","word"},{"a.hwp","hangul"},{"a.txt","text"},{"a.PDF","pdf"},{"a.tar.gz","archive"},
            {"a.RAR","archive"},{"a.CBZ","comic"},{"a.epub","book"},{"a.APK","android"},
            {"a.ai","design"},{"a.srt","subtitle"},{"a.jpg","image"},{"a.mkv","video"},{"a.mp3","audio"},
            {"a.unknown","file"},{"README","file"}};
        for(String[] row:rows)assertEquals(row[0],row[1],FileIconType.kind(row[0]));
        assertEquals("file",FileIconType.kind(null));
    }
}
