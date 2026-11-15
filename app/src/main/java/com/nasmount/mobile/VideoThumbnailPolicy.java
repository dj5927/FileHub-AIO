package com.nasmount.mobile;

/** Limits automatic video previews, especially for metered NAS connections. */
final class VideoThumbnailPolicy {
    static final int BLOCK_BYTES = 192 * 1024;
    static final int MAX_REMOTE_BLOCKS = 32; // At most 6 MiB transferred per attempt.
    static final int IN_MEMORY_BLOCKS = 24;
    static final long REMOTE_DEADLINE_MS = 12_000L;

    private VideoThumbnailPolicy() {}

    static boolean eligible(String filename, boolean directory) {
        return !directory && VideoEpisodeQueue.isVideo(filename);
    }

    static boolean remoteEligible(long fileBytes) {
        return fileBytes > 0;
    }

    static boolean validRead(long size, long position, int requested) {
        return size > 0 && position >= 0 && position < size && requested > 0;
    }
}
