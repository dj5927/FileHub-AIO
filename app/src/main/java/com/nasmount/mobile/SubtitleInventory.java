package com.nasmount.mobile;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folder-level subtitle badges without scanning remote movie data.
 *
 * External subtitles are determined solely from the sibling filenames already
 * returned by the folder listing (including hidden subtitle files).
 * Embedded subtitles are only claimed after an actual local probe or Media3
 * has reported a text track while no external sidecar was attached.
 */
final class SubtitleInventory {
    private static final Set<String> EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
            "srt", "smi", "vtt", "ass", "ssa", "sub", "sup", "idx", "txt"));
    private static final Set<String> LANGUAGE_SUFFIXES = new LinkedHashSet<>(Arrays.asList(
            "ko", "kor", "en", "eng", "ja", "jpn", "zh", "zho", "chi",
            "fr", "fre", "de", "ger", "es", "spa", "pt", "ru", "rus",
            "vi", "th", "id", "forced", "sdh", "hi", "default"));

    private static final String PREFS = "video_subtitle_inventory";
    private SubtitleInventory() {}

    static Map<String, Set<String>> fromSiblings(Collection<String> filenames) {
        if (filenames == null || filenames.isEmpty()) return Collections.emptyMap();
        Map<String, Set<String>> index = new HashMap<>();
        for (String filename : filenames) {
            if (filename == null) continue;
            int extIndex = filename.lastIndexOf('.');
            if (extIndex <= 0) continue;
            String ext = filename.substring(extIndex + 1).toLowerCase(Locale.ROOT);
            if (!EXTENSIONS.contains(ext)) continue;
            String stem = filename.substring(0, extIndex).toLowerCase(Locale.ROOT);
            add(index, stem, ext);
            // Movie.en.srt, Movie.ko-KR.srt and Movie.forced.srt.
            String normalized = stem;
            for (int depth = 0; depth < 2; depth++) {
                int separator = normalized.lastIndexOf('.');
                if (separator <= 0) break;
                String token = normalized.substring(separator + 1);
                String language = token.split("[-_]")[0];
                if (!LANGUAGE_SUFFIXES.contains(language)) break;
                normalized = normalized.substring(0, separator);
                add(index, normalized, ext);
            }
        }
        return index;
    }

    private static void add(Map<String, Set<String>> index, String stem, String ext) {
        index.computeIfAbsent(stem, k -> new LinkedHashSet<>())
                .add(ext.toUpperCase(Locale.ROOT));
    }

    static String externalLabel(Map<String, Set<String>> index, String videoFilename) {
        if (index == null || videoFilename == null) return "";
        int dot = videoFilename.lastIndexOf('.');
        String base = (dot <= 0 ? videoFilename : videoFilename.substring(0, dot))
                .toLowerCase(Locale.ROOT);
        Set<String> formats = index.get(base);
        if (formats == null || formats.isEmpty()) return "";
        List<String> names = new ArrayList<>(formats);
        Collections.sort(names);
        return "외부 자막 " + String.join("/", names);
    }

    static boolean isTextSubtitleMime(String mime) {
        if (mime == null) return false;
        String lower = mime.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/") ||
                lower.contains("subrip") || lower.contains("subtitle") ||
                lower.contains("substation") || lower.contains("cea-") ||
                lower.contains("vobsub") || lower.contains("pgs") ||
                lower.contains("dvbsubs");
    }

    static int status(Context context, String id) {
        if (id == null || id.isEmpty()) return -1;
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(id, -1);
    }

    static void record(Context context, String id, boolean embedded) {
        if (id == null || id.isEmpty()) return;
        if (embedded || status(context, id) != 1)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(id, embedded ? 1 : 0).apply();
    }

    static String entryId(Models.Entry entry, Models.RemoteProfile remote) {
        if (entry == null || entry.directory) return null;
        String identity;
        if (remote != null) {
            identity = "remote:" + remote.id + ":" + entry.path + ":" + entry.size;
        } else if (entry.file != null) {
            identity = "local:" + Uri.fromFile(entry.file);
        } else if (entry.document != null) {
            identity = "local:" + entry.document.getUri();
        } else if (entry.uri != null) {
            identity = "local:" + entry.uri;
        } else return null;
        return PlaybackProgressStore.idFor(identity);
    }

    static boolean canProbeLocally(Models.Entry entry) {
        return entry != null && (entry.file != null || entry.document != null ||
                entry.uri != null);
    }

    static boolean probeLocal(Context context, Models.Entry entry) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (entry.file != null) {
                File file = entry.file;
                extractor.setDataSource(file.getAbsolutePath());
            } else {
                Uri uri = entry.document != null ? entry.document.getUri() : entry.uri;
                extractor.setDataSource(context, uri, null);
            }
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                MediaFormat format = extractor.getTrackFormat(track);
                if (format.containsKey(MediaFormat.KEY_MIME) &&
                        isTextSubtitleMime(format.getString(MediaFormat.KEY_MIME)))
                    return true;
            }
        } catch (Exception notRecognized) {
            // Android MediaExtractor can miss Matroska PGS/ASS/VobSub;
            // fall back to a bounded EBML header read below.
        } finally {
            extractor.release();
        }
        if (!MatroskaSubtitleProbe.supportsName(entry.name)) return false;
        try (InputStream input = entry.file != null ?
                new FileInputStream(entry.file) :
                context.getContentResolver().openInputStream(
                        entry.document != null ? entry.document.getUri() : entry.uri)) {
            return input != null && MatroskaSubtitleProbe.headerContainsSubtitles(input);
        }
    }
}
