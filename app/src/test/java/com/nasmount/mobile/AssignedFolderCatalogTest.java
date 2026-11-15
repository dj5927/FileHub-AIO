package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class AssignedFolderCatalogTest {
    @Test public void videosUseExistingLocalAndRemoteMarks(){
        Set<String> scopes=new HashSet<>(Arrays.asList(
                "local:/storage/emulated/0/Movies",
                "remote:nas-1:/2026/Drama"));
        List<AssignedFolderCatalog.Folder> rows=
                AssignedFolderCatalog.collect("video",scopes,Collections.emptyList());
        assertEquals(2,rows.size());
        assertEquals("Drama",rows.get(0).name());
        assertEquals("nas-1",rows.get(0).owner);
        assertEquals("local",rows.get(1).kind);
    }
    @Test public void comicsAndNovelsStaySeparateOnSameFolder(){
        List<ReaderFolders.Scope> scopes=Arrays.asList(
                new ReaderFolders.Scope("comic","local","","/Books"),
                new ReaderFolders.Scope("text","local","","/Books"));
        assertEquals(1,AssignedFolderCatalog.collect(
                "comic",Collections.emptySet(),scopes).size());
        assertEquals(1,AssignedFolderCatalog.collect(
                "text",Collections.emptySet(),scopes).size());
    }
    @Test public void cloudAccountScopesNeverMerge(){
        List<ReaderFolders.Scope> scopes=Arrays.asList(
                new ReaderFolders.Scope("comic","remote","id-A","/Manga"),
                new ReaderFolders.Scope("comic","remote","id-B","/Manga"));
        assertEquals(2,AssignedFolderCatalog.collect(
                "comic",Collections.emptySet(),scopes).size());
    }
    @Test public void legacySafUriAndStorageDocumentIdAreRecognized(){
        AssignedFolderCatalog.Folder saf=AssignedFolderCatalog.fromVideoScope(
                "saf:content://com.android.externalstorage.documents/tree/primary%3AComics:/Part1");
        assertNotNull(saf);
        assertEquals("saf",saf.kind);
        assertEquals("/Part1",saf.path);
        AssignedFolderCatalog.Folder storage=AssignedFolderCatalog.fromVideoScope(
                "storage:primary:Movies/Drama");
        assertEquals("storage",storage.kind);
        assertEquals("Drama",storage.name());
    }
    @Test public void invalidScopesAreIgnored(){
        assertNull(AssignedFolderCatalog.fromVideoScope(""));
        assertNull(AssignedFolderCatalog.fromVideoScope("remote:/"));
        assertTrue(AssignedFolderCatalog.collect("video",
                new HashSet<>(Arrays.asList("invalid")),
                Collections.emptyList()).isEmpty());
    }
    @Test public void markedVideoRootFoldersRemainVisibleAfterNormalization(){
        AssignedFolderCatalog.Folder local=
                AssignedFolderCatalog.fromVideoScope("local:/");
        assertNotNull(local);
        assertEquals("/",local.path);
        AssignedFolderCatalog.Folder remote=
                AssignedFolderCatalog.fromVideoScope("remote:provider-1:/");
        assertNotNull(remote);
        assertEquals("/",remote.path);
        assertEquals("provider-1",remote.owner);
    }
    @Test public void safRootPrefixDoesNotMatchSiblingDocument(){
        assertEquals("/Chapter",AssignedFolderCatalog.relativeDocumentId(
                "primary:Books","primary:Books/Chapter"));
        assertEquals("/Novels",AssignedFolderCatalog.relativeDocumentId(
                "primary:","primary:Novels"));
        assertNull(AssignedFolderCatalog.relativeDocumentId(
                "primary:Books","primary:Books2/Chapter"));
        assertEquals("/",AssignedFolderCatalog.relativeDocumentId(
                "primary:Books","primary:Books"));
    }
}
