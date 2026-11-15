package com.nasmount.mobile;

import org.junit.Test;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class MatroskaSubtitleProbeTest {
    private static byte[] mkvWithTrackType(int type) {
        return new byte[]{
                0x1A, 0x45, (byte)0xDF, (byte)0xA3, (byte)0x80,
                0x18, 0x53, (byte)0x80, 0x67, (byte)0xFF,
                0x16, 0x54, (byte)0xAE, 0x6B, (byte)0x88,
                (byte)0xAE, (byte)0x86,
                (byte)0xD7, (byte)0x81, 0x01,
                (byte)0x83, (byte)0x81, (byte)type
        };
    }
    @Test public void subtitleTrackInEarlyHeaderIsDetected() {
        assertTrue(MatroskaSubtitleProbe.containsSubtitleTrack(mkvWithTrackType(17)));
    }
    @Test public void videoAndAudioTrackAreNotSubtitles() {
        assertFalse(MatroskaSubtitleProbe.containsSubtitleTrack(mkvWithTrackType(1)));
        assertFalse(MatroskaSubtitleProbe.containsSubtitleTrack(mkvWithTrackType(2)));
    }
    @Test public void corruptedOrNonMkvDataNeverClaimsSubtitle() {
        assertFalse(MatroskaSubtitleProbe.containsSubtitleTrack(new byte[]{1, 2, 3}));
        byte[] invalid = mkvWithTrackType(17);
        invalid[0] = 0;
        assertFalse(MatroskaSubtitleProbe.containsSubtitleTrack(invalid));
    }
    @Test public void probesOnlyMatroskaFamily() {
        assertTrue(MatroskaSubtitleProbe.supportsName("film.MKV"));
        assertTrue(MatroskaSubtitleProbe.supportsName("video.webm"));
        assertFalse(MatroskaSubtitleProbe.supportsName("video.mp4"));
        assertFalse(MatroskaSubtitleProbe.supportsName("video.mkv.part"));
    }

    @Test public void readsRealFfmpegMuxedMkvWithSrtTrack() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/fixture_mkv_with_srt.mkv")) {
            assertNotNull(stream);
            assertTrue(MatroskaSubtitleProbe.headerContainsSubtitles(stream));
        }
    }

    @Test public void doesNotMislabelRealVideoOnlyMkv() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/fixture_mkv_video_only.mkv")) {
            assertNotNull(stream);
            assertFalse(MatroskaSubtitleProbe.headerContainsSubtitles(stream));
        }
    }

    private static byte[] fixture(String filename) throws Exception {
        try (InputStream stream = MatroskaSubtitleProbeTest.class
                .getResourceAsStream("/" + filename);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            assertNotNull(stream);
            byte[] buf = new byte[1024];
            int read;
            while ((read = stream.read(buf)) != -1) bytes.write(buf, 0, read);
            return bytes.toByteArray();
        }
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        int bytesRead;
        CountingInputStream(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] dst, int offset, int count) {
            int result = super.read(dst, offset, count);
            if (result > 0) bytesRead += result;
            return result;
        }
    }

    @Test public void oneEmbeddedTrackStopsBeforeFullMegabyteRead() throws Exception {
        byte[] header = fixture("fixture_mkv_with_srt.mkv");
        byte[] hugeMovie = Arrays.copyOf(header, 2 * 1024 * 1024);
        CountingInputStream network = new CountingInputStream(hugeMovie);
        assertTrue(MatroskaSubtitleProbe.headerContainsSubtitles(network));
        assertTrue("Extra bytes: " + network.bytesRead, network.bytesRead <= 16384);
    }

    @Test public void singleSubtitleBeyondFirstMegabyteCanBeFound() throws Exception {
        byte[] mkv = mkvWithTrackType(17);
        int padding = 1200000;
        byte[] delayed = new byte[10 + 5 + padding + mkv.length - 10];
        System.arraycopy(mkv, 0, delayed, 0, 10);
        // An actual EBML Void element: ID=0xEC, size encoded as a 4-byte
        // VINT. Its 1.2MB payload is intentionally before the Tracks element.
        int cursor = 10;
        delayed[cursor++] = (byte)0xEC;
        int size = padding | 0x10000000;
        delayed[cursor++] = (byte)(size >>> 24);
        delayed[cursor++] = (byte)(size >>> 16);
        delayed[cursor++] = (byte)(size >>> 8);
        delayed[cursor++] = (byte)size;
        System.arraycopy(mkv, 10, delayed, cursor + padding, mkv.length - 10);
        assertTrue(MatroskaSubtitleProbe.headerContainsSubtitles(
                new ByteArrayInputStream(delayed)));
    }

    @Test public void seekHeadTrackReferenceDoesNotHideSingleRealSubtitle()
            throws Exception {
        byte[] mkv = mkvWithTrackType(17);
        byte[] seekHead = {
                0x11, 0x4D, (byte)0x9B, 0x74, (byte)0x8A,
                0x4D, (byte)0xBB, (byte)0x87,
                0x53, (byte)0xAB, (byte)0x84,
                0x16, 0x54, (byte)0xAE, 0x6B
        };
        byte[] full = new byte[mkv.length + seekHead.length];
        System.arraycopy(mkv, 0, full, 0, 10);
        System.arraycopy(seekHead, 0, full, 10, seekHead.length);
        System.arraycopy(mkv, 10, full, 10 + seekHead.length, mkv.length - 10);
        assertTrue(MatroskaSubtitleProbe.headerContainsSubtitles(
                new ByteArrayInputStream(full)));
    }
}
