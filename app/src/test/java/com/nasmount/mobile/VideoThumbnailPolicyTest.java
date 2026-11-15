package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoThumbnailPolicyTest {
    @Test public void videoExtensionsGetPreviewsAndFoldersDoNot() {
        assertTrue(VideoThumbnailPolicy.eligible("movie.mp4", false));
        assertTrue(VideoThumbnailPolicy.eligible("clip.mkv", false));
        assertFalse(VideoThumbnailPolicy.eligible("movie.mp4", true));
        assertFalse(VideoThumbnailPolicy.eligible("notes.txt", false));
    }

    @Test public void remotePreviewNeedsKnownPositiveFileSize() {
        assertFalse(VideoThumbnailPolicy.remoteEligible(-1));
        assertFalse(VideoThumbnailPolicy.remoteEligible(0));
        assertTrue(VideoThumbnailPolicy.remoteEligible(4096));
    }

    @Test public void rangeReadsNeverEscapeFile() {
        assertTrue(VideoThumbnailPolicy.validRead(1024, 0, 10));
        assertTrue(VideoThumbnailPolicy.validRead(1024, 1023, 1));
        assertFalse(VideoThumbnailPolicy.validRead(1024, 1024, 1));
        assertFalse(VideoThumbnailPolicy.validRead(1024, -1, 1));
        assertFalse(VideoThumbnailPolicy.validRead(1024, 0, 0));
    }

    @Test public void remotePreviewHasBoundedTransferBudget() {
        assertTrue(VideoThumbnailPolicy.BLOCK_BYTES > 0);
        assertTrue(VideoThumbnailPolicy.MAX_REMOTE_BLOCKS > 0);
        assertTrue((long) VideoThumbnailPolicy.BLOCK_BYTES *
                VideoThumbnailPolicy.MAX_REMOTE_BLOCKS <= 8L * 1024 * 1024);
    }
}
