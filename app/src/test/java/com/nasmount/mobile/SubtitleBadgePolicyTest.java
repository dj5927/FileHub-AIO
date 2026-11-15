package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SubtitleBadgePolicyTest {
    @Test public void onlyExternalMakesGreenBadge() {
        assertEquals(SubtitleBadgePolicy.EXTERNAL,
                SubtitleBadgePolicy.flags(true, false));
    }
    @Test public void exactlyOneEmbeddedTrackMakesRedBadge() {
        assertEquals(SubtitleBadgePolicy.EMBEDDED,
                SubtitleBadgePolicy.flags(false, true));
    }
    @Test public void oneEmbeddedAndOneSidecarShowTwoIndependentBadges() {
        assertEquals(SubtitleBadgePolicy.EXTERNAL | SubtitleBadgePolicy.EMBEDDED,
                SubtitleBadgePolicy.flags(true, true));
    }
    @Test public void noDetectedTracksGivesNoBadge() {
        assertEquals(0, SubtitleBadgePolicy.flags(false, false));
    }
}
