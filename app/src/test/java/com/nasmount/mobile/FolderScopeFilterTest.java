package com.nasmount.mobile;

import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

public final class FolderScopeFilterTest {
    @Test public void hidesOnlySubtitleAndRequestedTextExtensions() {
        assertTrue(FolderScopeFilter.isSubtitleDocument("Movie.KO.SRT"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("track.smi"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("notes.TXT"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("show.ass"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("blu-ray.SUP"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("disc.idx"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("movie.NFO"));
        assertTrue(FolderScopeFilter.isSubtitleDocument("movie.info.nfo"));
        assertFalse(FolderScopeFilter.isSubtitleDocument("movie.nfo.bak"));
        assertFalse(FolderScopeFilter.isSubtitleDocument("movie.mp4"));
        assertFalse(FolderScopeFilter.isSubtitleDocument("subtitle.srt.bak"));
    }

    @Test public void scopedRuleIncludesChildrenNotSiblingFolders() {
        Set<String> folders = new HashSet<>();
        folders.add("local:/storage/emulated/0/Movies");
        assertTrue(FolderScopeFilter.folderCovered(
                "local:/storage/emulated/0/Movies", folders));
        assertTrue(FolderScopeFilter.folderCovered(
                "local:/storage/emulated/0/Movies/Korea", folders));
        assertFalse(FolderScopeFilter.folderCovered(
                "local:/storage/emulated/0/Movies2", folders));
        assertFalse(FolderScopeFilter.folderCovered(
                "local:/storage/emulated/0/Music", folders));
    }

    @Test public void remoteScopeIsIsolatedByConnectionId() {
        Set<String> folders = new HashSet<>();
        folders.add("remote:id1:/films");
        assertTrue(FolderScopeFilter.folderCovered("remote:id1:/films/test", folders));
        assertFalse(FolderScopeFilter.folderCovered("remote:id2:/films", folders));
        assertFalse(FolderScopeFilter.folderCovered("remote:id1:/films-other", folders));
    }

    @Test public void videoFolderIncludesSafChildButNotSibling() {
        Set<String> scopes = new HashSet<>();
        scopes.add("storage:primary:Movies/2026");
        assertTrue(FolderScopeFilter.folderCovered(
                "storage:primary:Movies/2026/Korean", scopes));
        assertFalse(FolderScopeFilter.folderCovered(
                "storage:primary:Movies/20260", scopes));
    }

    @Test public void trailingSlashAndWindowsPathAreNormalized() {
        assertEquals("local:/some/Movies",
                FolderScopeFilter.normalize("local:\\some\\Movies\\"));
    }
}
