package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SubtitleSourcePolicyTest {
    @Test public void autoAlwaysPrefersExternalEvenWithTwoKoreanTracks() {
        assertEquals(SubtitleSourcePolicy.EXTERNAL,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.AUTO, true, true));
        assertEquals(SubtitleSourcePolicy.EXTERNAL,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.AUTO, true, false));
        assertEquals(SubtitleSourcePolicy.EMBEDDED,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.AUTO, false, true));
        assertEquals(SubtitleSourcePolicy.NONE,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.AUTO, false, false));
    }

    @Test public void manualEmbeddedOverridesAutomaticExternalPriority() {
        assertEquals(SubtitleSourcePolicy.EMBEDDED,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.EMBEDDED, true, true));
        assertEquals(SubtitleSourcePolicy.NONE,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.EMBEDDED, true, false));
    }

    @Test public void manualExternalDoesNotUnexpectedlyFallBackToInternal() {
        assertEquals(SubtitleSourcePolicy.EXTERNAL,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.EXTERNAL, true, true));
        assertEquals(SubtitleSourcePolicy.NONE,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.EXTERNAL, false, true));
    }

    @Test public void offDisablesEveryTrackAndInvalidPreferenceUsesAuto() {
        assertEquals(SubtitleSourcePolicy.NONE,
                SubtitleSourcePolicy.desiredSource(SubtitleSourcePolicy.OFF, true, true));
        assertEquals(SubtitleSourcePolicy.AUTO, SubtitleSourcePolicy.normalize(99));
    }

    @Test public void recognizesOnlyOurTaggedSidecarAsExternal() {
        assertTrue(SubtitleSourcePolicy.isExternal(
                SubtitleSourcePolicy.EXTERNAL_TRACK_ID, "Korean"));
        assertTrue(SubtitleSourcePolicy.isExternal(
                null, SubtitleSourcePolicy.EXTERNAL_LABEL_PREFIX + " SRT"));
        assertFalse(SubtitleSourcePolicy.isExternal("1", "한국어"));
        assertFalse(SubtitleSourcePolicy.isExternal(null, null));
    }

    @Test public void languageMatchingSupportsCommonMedia3Codes() {
        assertTrue(SubtitleSourcePolicy.matchesLanguage("kor", "ko"));
        assertTrue(SubtitleSourcePolicy.matchesLanguage("eng", "en"));
        assertTrue(SubtitleSourcePolicy.matchesLanguage("ja-JP", "ja"));
        assertFalse(SubtitleSourcePolicy.matchesLanguage("en", "ko"));
    }

    @Test public void autoFindsLanguageTaggedSidecarWithoutFalsePrefix() {
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.srt"));
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.ko.srt"));
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "[Moozzi2] Anohana - 01 (BD 1920x1080 x.264 FLACx2)",
                "[Moozzi2] Anohana - 01 (BD 1920x1080 x.264 FLACx2).smi"));
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.ko.smi"));
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.en.vtt"));
        assertTrue(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.ko-KR.forced.srt"));
        assertFalse(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.20250.srt"));
        assertFalse(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.nfo"));
        assertFalse(SubtitleSourcePolicy.matchesExternalFilename(
                "Movie.2025", "Movie.2025.extra.srt"));
    }
}
