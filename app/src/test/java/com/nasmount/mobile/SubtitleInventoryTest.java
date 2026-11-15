package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import static org.junit.Assert.*;

public final class SubtitleInventoryTest {
    @Test public void findsSameNamedSrtAndLanguageSuffixes() {
        Map<String, Set<String>> index = SubtitleInventory.fromSiblings(Arrays.asList(
                "Movie.2026.mkv", "Movie.2026.ko.srt", "Movie.2026.en.ASS",
                "Other.srt", "Movie.2026.ja.vtt"));
        assertEquals("외부 자막 ASS/SRT/VTT",
                SubtitleInventory.externalLabel(index, "Movie.2026.mkv"));
        assertEquals("외부 자막 SRT",
                SubtitleInventory.externalLabel(index, "Other.mp4"));
    }
    @Test public void avoidsFalsePrefixAndUnrelatedNames() {
        Map<String, Set<String>> index = SubtitleInventory.fromSiblings(Arrays.asList(
                "Movie2.srt", "BadSRT.mp4", "README.txt"));
        assertEquals("", SubtitleInventory.externalLabel(index, "Movie.mp4"));
        assertEquals("", SubtitleInventory.externalLabel(index, "BadSRT.mp4"));
    }
    @Test public void detectsKoreanLocaleAndSpecialSubFormats() {
        Map<String, Set<String>> index = SubtitleInventory.fromSiblings(Arrays.asList(
                "한글영화.ko-KR.smi", "한글영화.forced.sup", "한글영화.sdh.srt"));
        assertEquals("외부 자막 SMI/SRT/SUP",
                SubtitleInventory.externalLabel(index, "한글영화.mkv"));
    }
    @Test public void distinguishesTextSubtitleTracksFromAudio() {
        assertTrue(SubtitleInventory.isTextSubtitleMime("application/x-subrip"));
        assertTrue(SubtitleInventory.isTextSubtitleMime("text/vtt"));
        assertTrue(SubtitleInventory.isTextSubtitleMime("application/pgs"));
        assertFalse(SubtitleInventory.isTextSubtitleMime("audio/eac3"));
        assertFalse(SubtitleInventory.isTextSubtitleMime("video/avc"));
    }
}
