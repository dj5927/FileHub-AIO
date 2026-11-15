package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FolderSettingsPolicyTest {
    @Test public void tenVolumesShareOneDirectoryProfile(){
        String a=FolderSettingsPolicy.scope("local","","/Books/A/1.zip");
        String b=FolderSettingsPolicy.scope("local","","/Books/A/10.zip");
        assertEquals(a,b);
        assertEquals("local||/Books/A",a);
        assertNotEquals(a,FolderSettingsPolicy.scope("local","","/Books/B/1.zip"));
    }
    @Test public void remoteProfilesAndModesRemainIsolated(){
        String a=FolderSettingsPolicy.scope("remote","nas-a","/Korean/1.txt");
        String b=FolderSettingsPolicy.scope("remote","nas-b","/Korean/1.txt");
        assertNotEquals(a,b);
        assertNotEquals(FolderSettingsPolicy.group("comic",a),
                FolderSettingsPolicy.group("text",a));
    }
    @Test public void safDocumentVolumeAndRootDirectory(){
        assertEquals("saf|provider|primary:Books",
                FolderSettingsPolicy.scope("saf","provider","primary:Books/vol1.zip"));
        assertEquals("saf|provider|primary:",
                FolderSettingsPolicy.scope("saf","provider","primary:root.epub"));
        assertNotEquals(FolderSettingsPolicy.scope("saf","provider",
                "A12:Books/vol1.zip"),FolderSettingsPolicy.scope("saf","provider",
                "primary:Books/vol1.zip"));
    }
    @Test public void localWindowsAndUnixSlashesNormalize(){
        assertEquals(FolderSettingsPolicy.scope("local","","C:\\Books\\A\\1.cbz"),
                FolderSettingsPolicy.scope("local","","C:/Books/A/2.cbz"));
        assertEquals("local||/",FolderSettingsPolicy.scope("local","","/book.cbz"));
        assertEquals("",FolderSettingsPolicy.scope(null,"","book.txt"));
    }
}
