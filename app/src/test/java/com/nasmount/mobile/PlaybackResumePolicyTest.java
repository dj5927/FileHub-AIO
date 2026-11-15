package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackResumePolicyTest {
    @Test public void promptsAfterTenSeconds() {
        assertFalse(PlaybackResumePolicy.canResume(9000, 120000));
        assertTrue(PlaybackResumePolicy.canResume(40000, 120000));
    }
    @Test public void doesNotPromptNearEndOrAfterFinish() {
        assertFalse(PlaybackResumePolicy.canResume(119000, 120000));
        assertFalse(PlaybackResumePolicy.canResume(121000, 120000));
    }
    @Test public void worksForShortClipsAndUnknownDuration() {
        assertTrue(PlaybackResumePolicy.canResume(12000, 30000));
        assertFalse(PlaybackResumePolicy.canResume(27000, 30000));
        assertTrue(PlaybackResumePolicy.canResume(12000, -1));
    }
}
