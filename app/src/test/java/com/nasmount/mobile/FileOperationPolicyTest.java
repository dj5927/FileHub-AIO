package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class FileOperationPolicyTest {
    @Test public void subfolderGuardRejectsOwnFolderAndDescendants() {
        assertTrue(FileOperationPolicy.childOrSame("/NAS/videos", "/NAS/videos"));
        assertTrue(FileOperationPolicy.childOrSame("/NAS/videos", "/NAS/videos/2026"));
        assertFalse(FileOperationPolicy.childOrSame("/NAS/videos", "/NAS/videos-old"));
        assertFalse(FileOperationPolicy.childOrSame("/NAS/videos", "/NAS"));
    }
    @Test public void remoteRootCoversEntireRemote() {
        assertTrue(FileOperationPolicy.childOrSame("/", "/movies"));
        assertTrue(FileOperationPolicy.childOrSame("/", "/"));
    }
    @Test public void windowsFilePathsNormalizeSeparators() {
        assertTrue(FileOperationPolicy.childOrSame(
                "C:\\Users\\User\\Folder", "C:\\Users\\User\\Folder\\inside"));
        assertFalse(FileOperationPolicy.childOrSame(
                "C:\\Users\\User\\Folder", "C:\\Users\\User\\Folder2"));
    }
    @Test public void batchNamesCannotSilentlyOverwriteCaseVariants() {
        assertThrows(IllegalArgumentException.class,
                () -> FileOperationPolicy.validateBatchNames(Arrays.asList(
                        "Movie.mp4", "movie.mp4")));
    }
    @Test public void rejectsTraversalOrSlashNames() {
        for (String name : Arrays.asList("..", "foo/bar", "x\\y", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> FileOperationPolicy.validateBatchNames(
                            Collections.singletonList(name)));
        }
    }
    @Test public void unicodeNamesAccepted() {
        FileOperationPolicy.validateBatchNames(
                Arrays.asList("강아지 기본사진", "일본도파민", "영상.mp4"));
    }
}
