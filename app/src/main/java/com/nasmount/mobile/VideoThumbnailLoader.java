package com.nasmount.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Real video stills from local files, SAF/MediaStore or seekable NAS streams. */
final class VideoThumbnailLoader {
    private static final int WIDTH = 224;
    private static final int HEIGHT = 144;
    private static final int MAX_CACHE_FILES = 160;

    private VideoThumbnailLoader() {}

    static Bitmap load(Context context, Models.Entry entry,
                       Models.RemoteProfile profile) {
        if (!VideoThumbnailPolicy.eligible(entry.name, entry.directory)) return null;
        boolean isRemote = entry.file == null && entry.document == null &&
                entry.uri == null;
        if (isRemote && (profile == null ||
                !VideoThumbnailPolicy.remoteEligible(entry.size))) return null;

        String scope = entry.file != null ? "file:" + entry.file.getAbsolutePath() :
                entry.document != null ? "saf:" + entry.document.getUri() :
                entry.uri != null ? "uri:" + entry.uri :
                "remote:" + profile.id + ":" + entry.path;
        File directory = new File(context.getCacheDir(), "video_previews");
        File cached = new File(directory, sha256(scope + ":" + entry.size +
                ":" + entry.modified) + ".jpg");
        if (cached.isFile()) {
            Bitmap bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (bitmap != null) {
                cached.setLastModified(System.currentTimeMillis());
                return bitmap;
            }
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap still = null;
        RemoteSource remoteSource = null;
        try {
            if (entry.file != null) {
                retriever.setDataSource(entry.file.getAbsolutePath());
            } else if (entry.document != null) {
                retriever.setDataSource(context, entry.document.getUri());
            } else if (entry.uri != null) {
                retriever.setDataSource(context, entry.uri);
            } else {
                remoteSource = new RemoteSource(profile, entry.path, entry.size);
                retriever.setDataSource(remoteSource);
            }
            // Prefer a recognizable scene over a black opening frame.
            long frameTime = 1_000_000L;
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                try {
                    long durationMs = Long.parseLong(duration);
                    if (durationMs > 10_000)
                        frameTime = Math.min(30_000_000L,
                                Math.max(1_000_000L, durationMs * 100L));
                } catch (NumberFormatException ignored) {}
            }
            still = frame(retriever, frameTime);
            if (still == null) {
                still = frame(retriever, 0);
            }
        } catch (Exception | LinkageError ignored) {
            // Missing codec, inaccessible provider or unsupported remote seek:
            // leave the ordinary video icon rather than blocking the list.
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
            if (remoteSource != null) try { remoteSource.close(); }
            catch (IOException ignored) {}
        }
        if (still != null && (directory.isDirectory() || directory.mkdirs())) {
            try (FileOutputStream output = new FileOutputStream(cached)) {
                still.compress(Bitmap.CompressFormat.JPEG, 82, output);
                prune(directory);
            } catch (Exception ignored) {}
        }
        return still;
    }

    private static Bitmap frame(MediaMetadataRetriever retriever, long timeUs) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            return retriever.getScaledFrameAtTime(timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT);
        }
        Bitmap original = retriever.getFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (original == null) return null;
        int width = original.getWidth(), height = original.getHeight();
        if (width <= WIDTH && height <= HEIGHT) return original;
        float factor = Math.min((float) WIDTH / width, (float) HEIGHT / height);
        Bitmap scaled = Bitmap.createScaledBitmap(original,
                Math.max(1, Math.round(width * factor)),
                Math.max(1, Math.round(height * factor)), true);
        if (scaled != original) original.recycle();
        return scaled;
    }

    private static void prune(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null || files.length <= MAX_CACHE_FILES) return;
        java.util.Arrays.sort(files,
                (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_CACHE_FILES; i++) files[i].delete();
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) key.append(String.format(java.util.Locale.ROOT,
                    "%02x", b & 0xff));
            return key.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Small random-access blocks; never download a whole NAS movie. */
    static final class RemoteSource extends MediaDataSource {
        private final Models.RemoteProfile profile;
        private final String path;
        private final long length;
        private final long startedAt = SystemClock.elapsedRealtime();
        private int fetchedBlocks;
        private boolean closed;
        private final LinkedHashMap<Long, byte[]> blocks =
                new LinkedHashMap<Long, byte[]>(32, 0.75f, true) {
                    @Override protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
                        return size() > VideoThumbnailPolicy.IN_MEMORY_BLOCKS;
                    }
                };

        RemoteSource(Models.RemoteProfile profile, String path, long length) {
            this.profile = profile;
            this.path = path;
            this.length = length;
        }

        @Override public synchronized int readAt(long position, byte[] buffer,
                                                  int offset, int size) throws IOException {
            if (closed || size == 0 || position >= length) return -1;
            if (!VideoThumbnailPolicy.validRead(length, position, size) ||
                    offset < 0 || size > buffer.length - offset)
                throw new IOException("Invalid thumbnail read");
            long blockNumber = position / VideoThumbnailPolicy.BLOCK_BYTES;
            byte[] chunk = blocks.get(blockNumber);
            if (chunk == null) chunk = fetch(blockNumber);
            int within = (int) (position % VideoThumbnailPolicy.BLOCK_BYTES);
            if (within >= chunk.length) return -1;
            int take = Math.min(size, chunk.length - within);
            System.arraycopy(chunk, within, buffer, offset, take);
            return take;
        }

        private byte[] fetch(long blockNumber) throws IOException {
            if (fetchedBlocks >= VideoThumbnailPolicy.MAX_REMOTE_BLOCKS ||
                    SystemClock.elapsedRealtime() - startedAt >
                            VideoThumbnailPolicy.REMOTE_DEADLINE_MS)
                throw new IOException("Remote video preview budget exhausted");
            fetchedBlocks++;
            long position = blockNumber * VideoThumbnailPolicy.BLOCK_BYTES;
            int expected = (int) Math.min(VideoThumbnailPolicy.BLOCK_BYTES,
                    length - position);
            byte[] bytes = new byte[expected];
            int copied = 0;
            try (InputStream input = RemoteProvider.openStream(profile, path, position)) {
                while (copied < expected) {
                    int n = input.read(bytes, copied, expected - copied);
                    if (n < 0) break;
                    if (n == 0) continue;
                    copied += n;
                }
            } catch (Exception error) {
                throw new IOException("Remote thumbnail read failed", error);
            }
            if (copied <= 0) throw new IOException("Empty thumbnail range");
            if (copied != expected) bytes = java.util.Arrays.copyOf(bytes, copied);
            blocks.put(blockNumber, bytes);
            return bytes;
        }

        @Override public long getSize() { return length; }

        @Override public synchronized void close() throws IOException {
            closed = true;
            blocks.clear();
        }
    }
}
