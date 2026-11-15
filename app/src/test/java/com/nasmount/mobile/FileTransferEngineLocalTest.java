package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FileTransferEngineLocalTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void multiFileCopyKeepsSources() throws Exception {
        File source = temp.newFolder("from");
        File to = temp.newFolder("to");
        File first = new File(source, "one.txt");
        File second = new File(source, "두번째.txt");
        Files.write(first.toPath(), "First".getBytes(StandardCharsets.UTF_8));
        Files.write(second.toPath(), "Second".getBytes(StandardCharsets.UTF_8));
        int count = FileTransferEngine.transfer(null,
                Arrays.asList(FileTransferEngine.local(first),
                        FileTransferEngine.local(second)),
                FileTransferEngine.localFolder(to), false);
        assertEquals(2, count);
        assertArrayEquals(Files.readAllBytes(first.toPath()),
                Files.readAllBytes(new File(to, "one.txt").toPath()));
        assertTrue(second.isFile());
        assertTrue(new File(to, "두번째.txt").isFile());
    }

    @Test public void movingLocalFileRemovesSourceAfterDestinationExists() throws Exception {
        File source = temp.newFolder("source");
        File dest = temp.newFolder("destination");
        File file = new File(source, "movie.mkv");
        Files.write(file.toPath(), new byte[]{1,2,3,4,5});
        assertEquals(1, FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(file)),
                FileTransferEngine.localFolder(dest), true));
        assertFalse(file.exists());
        assertArrayEquals(new byte[]{1,2,3,4,5},
                Files.readAllBytes(new File(dest, "movie.mkv").toPath()));
    }

    @Test public void copyDirectoryIncludesNestedFiles() throws Exception {
        File source = temp.newFolder("root");
        File folder = new File(source, "한국영상");
        File nested = new File(folder, "another");
        assertTrue(nested.mkdirs());
        Files.write(new File(nested, "clip.mp4").toPath(), new byte[]{0,1,2,3});
        File dest = temp.newFolder("destination");
        assertEquals(1, FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(folder)),
                FileTransferEngine.localFolder(dest), false));
        assertArrayEquals(new byte[]{0,1,2,3},
                Files.readAllBytes(new File(dest, "한국영상/another/clip.mp4").toPath()));
    }

    @Test public void existingFilePreventsOverwriteOrPartialBatchCopy() throws Exception {
        File source = temp.newFolder("source");
        File dest = temp.newFolder("dest");
        File one = new File(source, "one");
        File two = new File(source, "two");
        Files.write(one.toPath(), new byte[]{1});
        Files.write(two.toPath(), new byte[]{2});
        File collision = new File(dest, "two");
        Files.write(collision.toPath(), new byte[]{99});
        assertThrows(IOException.class, () -> FileTransferEngine.transfer(null,
                Arrays.asList(FileTransferEngine.local(one),
                        FileTransferEngine.local(two)),
                FileTransferEngine.localFolder(dest), false));
        assertFalse(new File(dest, "one").exists());
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(collision.toPath()));
        assertTrue(one.exists());
    }

    @Test public void cannotCopyDirectoryInsideItself() throws Exception {
        File folder = temp.newFolder("inside");
        File nested = new File(folder, "sub");
        assertTrue(nested.mkdir());
        assertThrows(IOException.class, () -> FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(folder)),
                FileTransferEngine.localFolder(nested), false));
    }

    @Test public void deleteSelectedFilesAndFolderRecursively() throws Exception {
        File folder = temp.newFolder("remove");
        Files.write(new File(folder, "child.txt").toPath(), new byte[]{1,2});
        assertEquals(1, FileTransferEngine.delete(null,
                Collections.singletonList(FileTransferEngine.local(folder))));
        assertFalse(folder.exists());
    }
}
