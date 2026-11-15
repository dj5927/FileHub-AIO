package com.nasmount.mobile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Bounded MKV/WebM header probe. Does not download the video.
 * Matroska Tracks normally precede Cluster. Read only as much of the leading
 * header as necessary, up to a 4 MiB safety cap. A *single* TrackEntry with
 * TrackType=17 proves that an embedded subtitle exists; other tracks don't
 * need to be parsed. Unlocated Tracks are inconclusive.
 */
final class MatroskaSubtitleProbe {
    static final int MAX_HEADER_BYTES = 4 * 1024 * 1024;
    private static final long TRACKS = 0x1654AE6BL;
    private static final long TRACK_ENTRY = 0xAEL;
    private static final long TRACK_TYPE = 0x83L;
    private static final long EBML_HEADER = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long CLUSTER = 0x1F43B675L;
    private MatroskaSubtitleProbe() {}

    static boolean supportsName(String name) {
        if (name == null) return false;
        String file = name.toLowerCase(Locale.ROOT);
        return file.endsWith(".mkv") || file.endsWith(".webm");
    }

    static boolean remoteHeaderContainsSubtitles(Models.RemoteProfile profile,
                                                 Models.Entry file) throws Exception {
        if (file.directory || !supportsName(file.name)) return false;
        try (InputStream input = RemoteProvider.openStream(profile, file.path, 0)) {
            return headerContainsSubtitles(input);
        }
    }

    static boolean headerContainsSubtitles(InputStream input) throws Exception {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384)) {
            byte[] chunk = new byte[8192];
            int zeroReads = 0;
            while (buffer.size() < MAX_HEADER_BYTES) {
                int count = input.read(chunk, 0,
                        Math.min(chunk.length, MAX_HEADER_BYTES - buffer.size()));
                if (count < 0) break;
                if (count == 0) {
                    if (++zeroReads >= 3) break;
                    continue;
                }
                zeroReads = 0;
                buffer.write(chunk, 0, count);
                // Crucial: return as soon as a single subtitle track appears.
                // Previously each MKV read 1 MiB even when Tracks was at 4 KiB;
                // queued NAS files could consequently take a long time to show.
                int result = analyze(buffer.toByteArray());
                if (result == FOUND) return true;
                if (result == COMPLETE_WITHOUT_SUBTITLES) return false;
            }
            return analyze(buffer.toByteArray()) == FOUND;
        }
    }

    static boolean containsSubtitleTrack(byte[] bytes) {
        return analyze(bytes) == FOUND;
    }

    private static final int NEED_MORE = 0;
    private static final int FOUND = 1;
    private static final int COMPLETE_WITHOUT_SUBTITLES = 2;

    private static int analyze(byte[] bytes) {
        if (bytes == null || bytes.length < 12) return NEED_MORE;
        Vint headerId = elementId(bytes, 0, bytes.length);
        if (headerId == null || headerId.value != EBML_HEADER) return NEED_MORE;
        Vint headerSize = size(bytes, headerId.width, bytes.length);
        if (headerSize == null || isUnknownSize(headerSize)) return NEED_MORE;
        int cursor = headerId.width + headerSize.width;
        if (headerSize.value > bytes.length - (long) cursor) return NEED_MORE;
        cursor += (int) headerSize.value;
        // Look for the actual Segment at the EBML top-level. An arbitrary
        // occurrence of 0x1654AE6B (e.g. SeekHead/SeekID referencing Tracks)
        // is NOT a Tracks element. The old byte-scan gave early false negatives.
        while (cursor < bytes.length) {
            Vint id = elementId(bytes, cursor, bytes.length);
            if (id == null) return NEED_MORE;
            Vint len = size(bytes, cursor + id.width, bytes.length);
            if (len == null) return NEED_MORE;
            int dataStart = cursor + id.width + len.width;
            if (id.value == SEGMENT)
                return analyzeSegment(bytes, dataStart, len);
            if (isUnknownSize(len) ||
                    len.value > bytes.length - (long)dataStart) return NEED_MORE;
            cursor = dataStart + (int) len.value;
        }
        return NEED_MORE;
    }

    private static int analyzeSegment(byte[] bytes, int cursor, Vint segmentSize) {
        long segmentEnd = isUnknownSize(segmentSize) ? Long.MAX_VALUE :
                (long) cursor + segmentSize.value;
        while (cursor < bytes.length && cursor < segmentEnd) {
            Vint id = elementId(bytes, cursor, bytes.length);
            if (id == null) return NEED_MORE;
            Vint len = size(bytes, cursor + id.width, bytes.length);
            if (len == null) return NEED_MORE;
            int start = cursor + id.width + len.width;
            if (start > bytes.length) return NEED_MORE;
            if (id.value == TRACKS) {
                int end = clampEnd(start, len.value, bytes.length);
                if (end > start && subtitleInTracks(bytes, start, end)) return FOUND;
                if (!isUnknownSize(len) &&
                        len.value <= bytes.length - (long)start)
                    return COMPLETE_WITHOUT_SUBTITLES;
                return NEED_MORE;
            }
            // Media clusters can be gigabytes large. Don't stream video frames
            // merely to seek a track list, which normally precedes Cluster.
            if (id.value == CLUSTER) return NEED_MORE;
            if (isUnknownSize(len) ||
                    len.value > bytes.length - (long)start) return NEED_MORE;
            cursor = start + (int) len.value;
        }
        return NEED_MORE;
    }

    private static boolean isUnknownSize(Vint size) {
        return size.value == (1L << (size.width * 7)) - 1;
    }

    private static boolean subtitleInTracks(byte[] bytes, int start, int end) {
        int cursor = start;
        while (cursor < end) {
            Vint id = elementId(bytes, cursor, end);
            if (id == null) break;
            Vint len = size(bytes, cursor + id.width, end);
            if (len == null) break;
            int childStart = cursor + id.width + len.width;
            if (childStart > end) break;
            int childEnd = clampEnd(childStart, len.value, end);
            if (id.value == TRACK_ENTRY &&
                    subtitleInTrackEntry(bytes, childStart, childEnd)) return true;
            if (childEnd <= cursor || childEnd > end) break;
            cursor = childEnd;
        }
        return false;
    }

    private static boolean subtitleInTrackEntry(byte[] bytes, int start, int end) {
        int cursor = start;
        while (cursor < end) {
            Vint id = elementId(bytes, cursor, end);
            if (id == null) break;
            Vint len = size(bytes, cursor + id.width, end);
            if (len == null) break;
            int valueStart = cursor + id.width + len.width;
            if (valueStart > end) break;
            int next = clampEnd(valueStart, len.value, end);
            if (id.value == TRACK_TYPE && len.value > 0 && len.value <= 8 &&
                    valueStart + len.value <= end) {
                long type = 0;
                for (int i = valueStart; i < valueStart + (int)len.value; i++)
                    type = (type << 8) | (bytes[i] & 0xFFL);
                if (type == 17L) return true;
            }
            if (next <= cursor) break;
            cursor = next;
        }
        return false;
    }

    private static int clampEnd(int start, long length, int end) {
        if (length < 0 || length >= end - (long)start) return end;
        return start + (int) length;
    }

    private static final class Vint {
        final int width;
        final long value;
        Vint(int width, long value) { this.width = width; this.value = value; }
    }

    private static Vint elementId(byte[] bytes, int offset, int end) {
        if (offset >= end) return null;
        int first = bytes[offset] & 0xFF;
        int width = width(first);
        if (width > 4 || offset + width > end) return null;
        long value = first;
        for (int i = 1; i < width; i++)
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        return new Vint(width, value);
    }

    private static Vint size(byte[] bytes, int offset, int end) {
        if (offset >= end) return null;
        int first = bytes[offset] & 0xFF;
        int width = width(first);
        if (width > 8 || offset + width > end) return null;
        long mask = (1L << (8 - width)) - 1;
        long value = first & mask;
        for (int i = 1; i < width; i++)
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        return new Vint(width, value);
    }

    private static int width(int first) {
        if (first == 0) return 9;
        int length = 1;
        for (int mask = 0x80; (first & mask) == 0; mask >>= 1) length++;
        return length;
    }
}
