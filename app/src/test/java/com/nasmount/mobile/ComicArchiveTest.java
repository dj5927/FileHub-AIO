package com.nasmount.mobile;

import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public final class ComicArchiveTest {
    @Test public void numericOrderingAndArchiveFileDetection() {
        List<String> names=new ArrayList<>(Arrays.asList(
                "chapter/10.jpg","chapter/2.png","chapter/01.jpg"));
        names.sort(ComicArchive::naturalCompare);
        assertEquals("chapter/01.jpg",names.get(0));
        assertEquals("chapter/2.png",names.get(1));
        assertEquals("chapter/10.jpg",names.get(2));
        assertTrue(ComicArchive.supports("manga.CBZ"));
        assertEquals(BuildConfig.DIRECT_EDITION, ComicArchive.supports("manga.CBR"));
        assertTrue(ComicArchive.supports("manga.CB7"));
        assertFalse(ComicArchive.supports("report.docx"));
    }
    @Test public void pageNamesRejectTraversalAndHiddenMetadata() {
        assertFalse(ComicArchive.isSafePage("../chapter/1.jpg"));
        assertFalse(ComicArchive.isSafePage("__MACOSX/1.png"));
        assertFalse(ComicArchive.isSafePage("C:/pictures/1.jpg"));
        assertFalse(ComicArchive.isSafePage("chapter/2.txt"));
        assertTrue(ComicArchive.isSafePage("권1/03.jpeg"));
    }
    @Test public void zipPagesCanReadWithoutExtractingArchive() throws Exception {
        File folder=new File(System.getProperty("java.io.tmpdir"),"nas-comic-unit-"+System.nanoTime());
        assertTrue(folder.mkdirs());
        File archive=new File(folder,"comic.zip");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))){
            for(String name:Arrays.asList("10.jpg","02.jpg","note.txt")){
                zip.putNextEntry(new ZipEntry(name));
                zip.write(("test-"+name).getBytes("UTF-8"));
                zip.closeEntry();
            }
        }
        List<String> pages=ComicArchive.index(archive,"comic.zip");
        assertEquals(2,pages.size());
        assertEquals("02.jpg",pages.get(0));
        File result=new File(folder,"single.page");
        ComicArchive.extractPage(archive,"comic.zip",pages.get(0),result);
        assertEquals("test-02.jpg",new String(java.nio.file.Files.readAllBytes(result.toPath()),"UTF-8"));
        assertTrue(archive.isFile());
    }
    @Test public void sevenZPagesReadOnDemand() throws Exception {
        File folder=new File(System.getProperty("java.io.tmpdir"),
                "nas-comic-seven-"+System.nanoTime());
        assertTrue(folder.mkdirs());
        File originals=new File(folder,"originals");
        assertTrue(originals.mkdir());
        java.nio.file.Files.write(new File(originals,"10.png").toPath(),
                "ten".getBytes("UTF-8"));
        java.nio.file.Files.write(new File(originals,"02.png").toPath(),
                "two".getBytes("UTF-8"));
        File archive=new File(folder,"book.7z");
        ArchiveCodec.create(originals,archive,"7z");
        List<String> pages=ComicArchive.index(archive,"book.cb7");
        assertEquals(2,pages.size());
        assertEquals("02.png",pages.get(0));
        File output=new File(folder,"one.page");
        ComicArchive.extractPage(archive,"book.cb7",pages.get(0),output);
        assertEquals("two",new String(java.nio.file.Files.readAllBytes(
                output.toPath()),"UTF-8"));
    }
    @Test public void comicExtensionsAreNormalArchivesOutsideRegisteredFolder() {
        assertTrue(ArchiveCodec.isArchive("new.cbz"));
        assertEquals(BuildConfig.DIRECT_EDITION, ArchiveCodec.isArchive("new.cbr"));
        assertTrue(ArchiveCodec.isArchive("new.cb7"));
    }
}
