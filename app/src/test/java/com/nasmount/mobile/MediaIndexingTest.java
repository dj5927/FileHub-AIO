package com.nasmount.mobile;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class MediaIndexingTest {
    @Test public void classifyNasVideosAsSystemVideoCollection() {
        for (String name : new String[]{"clip.mp4","film.MKV","movie.mov",
                "camera.3gp","stream.ts","archive.m2ts","show.webm"}) {
            assertEquals(name, MediaIndexing.Category.VIDEO, MediaIndexing.category(name));
            assertTrue(MediaIndexing.mime(name).startsWith("video/"));
        }
    }

    @Test public void allMediaDownloadsUseAndroidMediaFolders() {
        assertEquals("Movies/FileHub AIO/",
                MediaIndexing.relativePath(MediaIndexing.Category.VIDEO));
        assertEquals("Pictures/FileHub AIO/",
                MediaIndexing.relativePath(MediaIndexing.Category.IMAGE));
        assertEquals("Music/FileHub AIO/",
                MediaIndexing.relativePath(MediaIndexing.Category.AUDIO));
        assertEquals("Download/",
                MediaIndexing.relativePath(MediaIndexing.Category.DOWNLOAD));
    }

    @Test public void imageAudioAndArbitraryFilesStayDistinct() {
        assertEquals(MediaIndexing.Category.IMAGE, MediaIndexing.category("사진.HEIC"));
        assertEquals(MediaIndexing.Category.AUDIO, MediaIndexing.category("song.flac"));
        assertEquals(MediaIndexing.Category.DOWNLOAD, MediaIndexing.category("subtitle.srt"));
        assertEquals(MediaIndexing.Category.DOWNLOAD, MediaIndexing.category("notes.txt"));
        assertEquals(MediaIndexing.Category.DOWNLOAD, MediaIndexing.category(null));
        assertEquals("video/x-matroska", MediaIndexing.mime("EP03.MKV"));
    }

    @Test public void headlessFileTransferTestsCanSkipMediaScan() throws Exception {
        File target = Files.createTempFile("filehub-index-", ".mp4").toFile();
        try {
            MediaIndexing.scanLocal(null, target);
            MediaIndexing.scanLocal(null, new File("missing.mp4"));
        } finally {
            target.delete();
        }
    }

    @Test public void alreadyCopiedVideoCanBeFoundForManualReindex()
            throws Exception {
        File folder = Files.createTempDirectory("filehub-reindex-").toFile();
        File nested = new File(folder, "nested");
        assertTrue(nested.mkdirs());
        File video = new File(nested, "clip.mp4");
        File subtitle = new File(nested, "clip.srt");
        assertTrue(video.createNewFile());
        assertTrue(subtitle.createNewFile());
        try {
            assertEquals(1, MediaIndexing.collectMediaFiles(folder).size());
            assertEquals(video.getCanonicalPath(),
                    MediaIndexing.collectMediaFiles(folder).get(0).getCanonicalPath());
            assertTrue(new File(nested, ".nomedia").createNewFile());
            assertEquals(0, MediaIndexing.collectMediaFiles(folder).size());
        } finally {
            new File(nested, ".nomedia").delete();
            subtitle.delete();
            video.delete();
            nested.delete();
            folder.delete();
        }
    }
}
