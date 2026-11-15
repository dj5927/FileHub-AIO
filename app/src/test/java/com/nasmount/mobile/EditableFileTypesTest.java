package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EditableFileTypesTest {
    @Test public void commonDocumentsOpenInEditor() {
        for (String name : new String[]{
                "메모.txt", "README.MD", "config.ini", "movie.srt", "movie.smi",
                "report.csv", "settings.json", "errors.log", "folder.nfo",
                "test.yaml", "LICENSE"}) {
            assertTrue("Should be editable: "+name, EditableFileTypes.isEditableText(name));
        }
    }
    @Test public void apkRoutesToInstallerNotTextEditor() {
        assertTrue(EditableFileTypes.isApk("NASMount_Android_v0.1.27.apk"));
        assertTrue(EditableFileTypes.isApk("UPGRADE.APK"));
        assertFalse(EditableFileTypes.isEditableText("UPGRADE.APK"));
    }
    @Test public void doNotEditMediaOrZipAsPlainText() {
        for (String name : new String[]{"a.mkv","a.mp4","a.zip","a.exe","a.apk",
                "a.bin","a.jpg","document.docx","package.apks"}) {
            assertFalse(EditableFileTypes.isEditableText(name));
        }
        assertFalse(EditableFileTypes.isApk("installer.apks"));
    }
}
