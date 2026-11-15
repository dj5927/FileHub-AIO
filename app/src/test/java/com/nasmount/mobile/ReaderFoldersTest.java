package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class ReaderFoldersTest {
    @Test public void onlySpecifiedAndDescendantFoldersActivateReader() throws Exception {
        List<ReaderFolders.Scope> scopes=Arrays.asList(
                new ReaderFolders.Scope("comic","local","","/storage/0/Comics"),
                new ReaderFolders.Scope("text","remote","id1","/Books"));
        assertTrue(ReaderFolders.contains(scopes,"comic","local","","/storage/0/Comics"));
        assertTrue(ReaderFolders.contains(scopes,"comic","local","","/storage/0/Comics/Volume1"));
        assertFalse(ReaderFolders.contains(scopes,"comic","local","","/storage/0/Comics2"));
        assertFalse(ReaderFolders.contains(scopes,"text","local","","/storage/0/Comics"));
        assertTrue(ReaderFolders.contains(scopes,"text","remote","id1","/Books/1"));
        assertFalse(ReaderFolders.contains(scopes,"text","remote","other","/Books/1"));
        assertFalse(ReaderFolders.contains(scopes,"comic","remote","id1","/Books"));
    }
    @Test public void safRequiresMatchingTreeAndIsSeparatedByMode() throws Exception {
        List<ReaderFolders.Scope> scopes=Arrays.asList(
                new ReaderFolders.Scope("comic","saf","content://sd/1","/Comics"));
        assertTrue(ReaderFolders.contains(scopes,"comic","saf","content://sd/1","/Comics/a"));
        assertFalse(ReaderFolders.contains(scopes,"comic","saf","content://sd/2","/Comics/a"));
    }
    @Test public void scopesRoundTripWithoutLosingPath() throws Exception {
        List<ReaderFolders.Scope> s=Arrays.asList(
                new ReaderFolders.Scope("text","remote","r1","/Novels"));
        List<ReaderFolders.Scope> restored=ReaderFolders.decode(ReaderFolders.encode(s));
        assertEquals("text",restored.get(0).mode);
        assertEquals("r1",restored.get(0).owner);
        assertEquals("/Novels",restored.get(0).path);
    }
}
