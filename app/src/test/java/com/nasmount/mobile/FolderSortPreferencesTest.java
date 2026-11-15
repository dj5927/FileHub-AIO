package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class FolderSortPreferencesTest {
    @Test public void overridesBelongOnlyToExactDirectory(){
        String downloads=FolderSortPreferences.key("local:/storage/emulated/0/Download");
        String parent=FolderSortPreferences.key("local:/storage/emulated/0");
        String child=FolderSortPreferences.key("local:/storage/emulated/0/Download/Books");
        assertNotEquals(downloads,parent);
        assertNotEquals(downloads,child);
        assertEquals(downloads,FolderSortPreferences.key(
                "local:/storage/emulated/0/Download"));
    }
    @Test public void sameNameDifferentProviderOrAccountDoesNotConflict(){
        String remoteOne=FolderSortPreferences.key("remote:cloudA:/Download");
        String remoteTwo=FolderSortPreferences.key("remote:cloudB:/Download");
        String saf=FolderSortPreferences.key("storage:primary:Download");
        assertNotEquals(remoteOne,remoteTwo);
        assertNotEquals(remoteOne,saf);
        assertNotEquals(saf,FolderSortPreferences.key(
                "local:/storage/emulated/0/Download"));
    }
    @Test public void globalsSurvivePerFolderSort(){
        assertEquals(FileViewOptions.DATE_NEW,FolderSortPreferences.resolve(
                FileViewOptions.NAME_ASC,FileViewOptions.DATE_NEW));
        assertEquals(FileViewOptions.NAME_ASC,FolderSortPreferences.resolve(
                FileViewOptions.NAME_ASC,null));
        assertEquals(FileViewOptions.NAME_ASC,FolderSortPreferences.resolve(
                FileViewOptions.NAME_ASC,99));
        assertEquals("",FolderSortPreferences.key(null));
        assertEquals("",FolderSortPreferences.key(""));
    }
}
