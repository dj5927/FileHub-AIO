package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.*;

public class FileTransferOverwriteTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void cancellingOverwriteDoesNotChangeOriginal() throws Exception {
        File from = temp.newFolder("from");
        File target = temp.newFolder("to");
        File source = new File(from, "video.mkv");
        File existing = new File(target, "video.mkv");
        Files.write(source.toPath(), new byte[]{1,2,3});
        Files.write(existing.toPath(), new byte[]{9,8,7});
        java.util.List<FileTransferEngine.Item> items =
                Collections.singletonList(FileTransferEngine.local(source));
        FileTransferEngine.Destination dest = FileTransferEngine.localFolder(target);
        assertEquals(Collections.singletonList("video.mkv"),
                FileTransferEngine.conflictingNames(items, dest, false));
        assertThrows(FileTransferEngine.NameConflict.class,
                () -> FileTransferEngine.transfer(null, items, dest, false));
        assertArrayEquals(new byte[]{9,8,7}, Files.readAllBytes(existing.toPath()));
        assertTrue(source.exists());
    }

    @Test public void confirmedOverwriteReplacesTargetWithoutDeletingSource()
            throws Exception {
        File from = temp.newFolder("from-approved");
        File target = temp.newFolder("to-approved");
        File source = new File(from, "video.mkv");
        File existing = new File(target, "video.mkv");
        Files.write(source.toPath(), new byte[]{1,2,3,4,5});
        Files.write(existing.toPath(), new byte[]{9,8,7});
        assertEquals(1, FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(source)),
                FileTransferEngine.localFolder(target), false, true));
        assertArrayEquals(new byte[]{1,2,3,4,5}, Files.readAllBytes(existing.toPath()));
        assertTrue(source.exists());
        assertEquals(1, target.listFiles().length);
    }

    @Test public void approvedMoveOverwritesAndDeletesSourceAfterSuccess()
            throws Exception {
        File from = temp.newFolder("move-from");
        File target = temp.newFolder("move-to");
        File source = new File(from, "note.txt");
        File existing = new File(target, "note.txt");
        Files.write(source.toPath(), new byte[]{3,4,5,6});
        Files.write(existing.toPath(), new byte[]{7,8});
        assertEquals(1, FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(source)),
                FileTransferEngine.localFolder(target), true, true));
        assertFalse(source.exists());
        assertArrayEquals(new byte[]{3,4,5,6}, Files.readAllBytes(existing.toPath()));
    }

    @Test public void approvedFolderMergeKeepsUnrelatedTargetFiles()
            throws Exception {
        File sourceRoot = temp.newFolder("merge-from");
        File targetRoot = temp.newFolder("merge-to");
        File sourceFolder = new File(sourceRoot, "movies");
        File targetFolder = new File(targetRoot, "movies");
        assertTrue(sourceFolder.mkdir());
        assertTrue(targetFolder.mkdir());
        Files.write(new File(sourceFolder, "clip.mkv").toPath(), new byte[]{1,2});
        Files.write(new File(sourceFolder, "new.srt").toPath(), new byte[]{3});
        Files.write(new File(targetFolder, "clip.mkv").toPath(), new byte[]{9});
        Files.write(new File(targetFolder, "unrelated.nfo").toPath(), new byte[]{8});
        assertEquals(1, FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(sourceFolder)),
                FileTransferEngine.localFolder(targetRoot), false, true));
        assertArrayEquals(new byte[]{1,2},
                Files.readAllBytes(new File(targetFolder, "clip.mkv").toPath()));
        assertArrayEquals(new byte[]{8},
                Files.readAllBytes(new File(targetFolder, "unrelated.nfo").toPath()));
        assertTrue(new File(targetFolder, "new.srt").exists());
    }

    @Test public void multiFileCopyReportsRealBytesAndOverallTotal()
            throws Exception {
        File from = temp.newFolder("progress-from");
        File target = temp.newFolder("progress-to");
        byte[] one = new byte[131072];
        byte[] two = new byte[32768];
        java.util.Arrays.fill(one, (byte) 7);
        java.util.Arrays.fill(two, (byte) 4);
        File a = new File(from, "a.mp4");
        File b = new File(from, "b.mp4");
        Files.write(a.toPath(), one);
        Files.write(b.toPath(), two);
        java.util.List<FileTransferEngine.Item> sources = java.util.Arrays.asList(
                FileTransferEngine.local(a), FileTransferEngine.local(b));
        final long[] last = {-1};
        final int[] completed = {-1};
        assertEquals(2, FileTransferEngine.transfer(null, sources,
                FileTransferEngine.localFolder(target), false, false,
                (transferred, total, name, count, items) -> {
                    assertEquals(163840, total);
                    assertTrue("progress must be monotonic", transferred >= last[0]);
                    assertTrue(transferred <= total);
                    last[0] = transferred;
                    completed[0] = count;
                }));
        assertArrayEquals(one, Files.readAllBytes(new File(target, "a.mp4").toPath()));
        assertArrayEquals(two, Files.readAllBytes(new File(target, "b.mp4").toPath()));
        assertEquals(163840, last[0]);
        assertEquals(2, completed[0]);
    }

    @Test public void cancellingMultiFileBatchKeepsCompletedFilesAndOriginals()
            throws Exception {
        File from = temp.newFolder("cancel-from");
        File target = temp.newFolder("cancel-to");
        File first = new File(from, "first.mp4");
        File second = new File(from, "second.mp4");
        Files.write(first.toPath(), new byte[]{1, 2, 3});
        Files.write(second.toPath(), new byte[]{4, 5, 6});
        FileTransferEngine.TransferControl cancellation =
                new FileTransferEngine.TransferControl();
        java.util.List<FileTransferEngine.Item> sources = java.util.Arrays.asList(
                FileTransferEngine.local(first), FileTransferEngine.local(second));
        assertThrows(FileTransferEngine.BatchFailure.class, () ->
                FileTransferEngine.transfer(null, sources,
                        FileTransferEngine.localFolder(target), false, false,
                        (done, total, name, completed, count) -> {
                            if (completed == 1) cancellation.cancel();
                        }, cancellation));
        // The fixture runs its copy on the JUnit thread; clear the intentional
        // interrupt before later JUnit tests execute on that same thread.
        Thread.interrupted();
        assertArrayEquals(new byte[]{1, 2, 3},
                Files.readAllBytes(new File(target, "first.mp4").toPath()));
        assertFalse(new File(target, "second.mp4").exists());
        assertTrue(first.exists());
        assertTrue(second.exists());
    }

    @Test public void conflictingFileAndDirectoryAreNeverSilentlyReplaced()
            throws Exception {
        File sourceRoot = temp.newFolder("type-from");
        File targetRoot = temp.newFolder("type-to");
        File source = new File(sourceRoot, "same");
        Files.write(source.toPath(), new byte[]{1});
        assertTrue(new File(targetRoot, "same").mkdir());
        assertThrows(java.io.IOException.class, () -> FileTransferEngine.transfer(null,
                Collections.singletonList(FileTransferEngine.local(source)),
                FileTransferEngine.localFolder(targetRoot), false, true));
        assertTrue(new File(targetRoot, "same").isDirectory());
    }
}
