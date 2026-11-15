package com.nasmount.mobile;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes completed user-visible NAS downloads and pasted media discoverable in
 * Android Gallery, system video pickers and other MediaStore clients.
 *
 * No index event is emitted for partial transfers or app-private temporary
 * files. Android's .nomedia and unsupported-format rules still apply.
 */
final class MediaIndexing {
    private MediaIndexing() {}

    enum Category { VIDEO, IMAGE, AUDIO, DOWNLOAD }

    static Category category(String filename) {
        if (VideoEpisodeQueue.isVideo(filename)) return Category.VIDEO;
        if (MediaFileTypes.isImage(filename)) return Category.IMAGE;
        if (MediaFileTypes.isAudio(filename)) return Category.AUDIO;
        return Category.DOWNLOAD;
    }

    static String relativePath(Category category) {
        // Android's public folder names are stable. Keep this policy pure Java:
        // local JVM unit-test android.jar has null stub Environment fields.
        switch (category) {
            case VIDEO: return "Movies/FileHub AIO/";
            case IMAGE: return "Pictures/FileHub AIO/";
            case AUDIO: return "Music/FileHub AIO/";
            default: return "Download/";
        }
    }

    /** Only called by the guarded Android 10+ MediaStore insertion path. */
    @androidx.annotation.RequiresApi(29)
    static Uri collection(Category category) {
        switch (category) {
            case VIDEO: return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case IMAGE: return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case AUDIO: return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            default: return MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        }
    }

    /** Consistent MIME for uncommon NAS file types not known by MimeTypeMap. */
    static String mime(String filename) {
        if (filename == null) return "application/octet-stream";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        switch (filename.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "mp4": return "video/mp4";
            case "m4v": return "video/x-m4v";
            case "mov": return "video/quicktime";
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "webm": return "video/webm";
            case "3gp": return "video/3gpp";
            case "ts": case "m2ts": return "video/mp2t";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "ogg": case "opus": return "audio/ogg";
            case "wma": return "audio/x-ms-wma";
            default: return "application/octet-stream";
        }
    }

    /**
     * Scan only a successfully completed public media file.
     * A null context is accepted by pure-Java unit tests.
     */
    static void scanLocal(Context context, File file) {
        if (context == null || file == null || !file.isFile() ||
                category(file.getName()) == Category.DOWNLOAD) return;
        MediaScannerConnection.scanFile(context,
                new String[]{file.getAbsolutePath()},
                new String[]{mime(file.getName())}, null);
    }

    /**
     * Explicit user-requested media reindex for already copied files.
     * A bounded walk avoids hanging on giant local storage trees. Android
     * media exclusion markers are respected.
     */
    static List<File> collectMediaFiles(File folder) {
        List<File> out = new ArrayList<>();
        walkMedia(folder, out, new int[]{20_000}, 0);
        return out;
    }

    private static void walkMedia(File folder, List<File> out,
                                  int[] budget, int depth) {
        if (folder == null || depth > 12 || out.size() >= 3000 ||
                budget[0] <= 0 || !folder.isDirectory() ||
                new File(folder, ".nomedia").isFile()) return;
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File file : children) {
            if (--budget[0] < 0 || out.size() >= 3000) break;
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) continue;
            if (file.isDirectory()) {
                walkMedia(file, out, budget, depth + 1);
            } else if (file.isFile() &&
                    category(file.getName()) != Category.DOWNLOAD) {
                out.add(file);
            }
        }
    }

    /** Returns the number of paths queued, not instant Gallery completion. */
    static int requestFolderScan(Context context, File folder) {
        List<File> files = collectMediaFiles(folder);
        if (files.isEmpty()) return 0;
        String[] paths = new String[files.size()];
        String[] types = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            paths[i] = files.get(i).getAbsolutePath();
            types[i] = mime(files.get(i).getName());
        }
        MediaScannerConnection.scanFile(context, paths, types, null);
        return files.size();
    }
}
