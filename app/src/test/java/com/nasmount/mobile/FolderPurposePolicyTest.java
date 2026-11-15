package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class FolderPurposePolicyTest {
    @Test public void videoOnlyShowsVideoBadgeOnExplicitlyMarkedFolder(){
        Set<String> video=new HashSet<>(Collections.singleton(
                "local:/storage/emulated/0/2.영화"));
        assertEquals(FolderPurposePolicy.VIDEO,
                FolderPurposePolicy.labels(video,Collections.emptyList(),
                        "local","","/storage/emulated/0/2.영화",
                        "local:/storage/emulated/0/2.영화"));
        assertEquals(0,FolderPurposePolicy.labels(video,Collections.emptyList(),
                "local","","/storage/emulated/0/2.영화/Drama",
                "local:/storage/emulated/0/2.영화/Drama"));
    }
    @Test public void comicAndTextShareOneReadingBadge() {
        List<ReaderFolders.Scope> scopes=Arrays.asList(
                new ReaderFolders.Scope("comic","remote","nas1","/Comics"),
                new ReaderFolders.Scope("text","remote","nas1","/Comics"));
        assertEquals(FolderPurposePolicy.READING,
                FolderPurposePolicy.labels(Collections.emptySet(),scopes,
                        "remote","nas1","/Comics","remote:nas1:/Comics"));
        assertEquals(0,FolderPurposePolicy.labels(Collections.emptySet(),scopes,
                "remote","nas2","/Comics","remote:nas2:/Comics"));
    }
    @Test public void bothBadgesAndSafTreeIsolation(){
        Set<String> video=new HashSet<>(Collections.singleton("storage:ABCD:Movies"));
        List<ReaderFolders.Scope> book=Collections.singletonList(
                new ReaderFolders.Scope("comic","saf","tree-A","/Movies"));
        assertEquals(FolderPurposePolicy.VIDEO|FolderPurposePolicy.READING,
                FolderPurposePolicy.labels(video,book,"saf","tree-A","/Movies",
                        "storage:ABCD:Movies"));
        assertEquals(FolderPurposePolicy.VIDEO,
                FolderPurposePolicy.labels(video,book,"saf","tree-B","/Movies",
                        "storage:ABCD:Movies"));
    }
    @Test public void similarlyPrefixedFoldersNotMarked(){
        Set<String> video=new HashSet<>(Collections.singleton("remote:one:/Books"));
        List<ReaderFolders.Scope> book=Collections.singletonList(
                new ReaderFolders.Scope("text","remote","one","/Books"));
        assertEquals(0,FolderPurposePolicy.labels(video,book,"remote","one",
                "/Books2","remote:one:/Books2"));
    }
}
