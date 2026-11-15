package com.nasmount.mobile;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PlaybackEndGuardTest {
    private static final long TWENTY_MIN = 20L * 60 * 1000;

    @Test public void nativeEndedImmediatelyShowsNextOnce() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertTrue(guard.sample(Player.STATE_ENDED, false, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertTrue(guard.handled());
        assertFalse(guard.sample(Player.STATE_ENDED, false, true,
                TWENTY_MIN, TWENTY_MIN, 500L));
    }

    @Test public void durationCrossingTriggersWithoutNativeEndedState() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN - 2000L, TWENTY_MIN, 4000L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN - 50L, TWENTY_MIN, 4100L));
        assertTrue(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 250L, TWENTY_MIN, 4400L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 4000L, TWENTY_MIN, 9000L));
    }

    @Test public void bufferingAfterEndAlsoTriggersFallback() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_BUFFERING, true, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertTrue(guard.sample(Player.STATE_BUFFERING, true, true,
                TWENTY_MIN + 300L, TWENTY_MIN, 500L));
    }

    @Test public void noDurationDoesNotInventEnd() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                500000L, C.TIME_UNSET, 100L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                700000L, C.TIME_UNSET, 2000L));
        assertFalse(guard.handled());
    }

    @Test public void pausedSeekNearEndDoesNotLaunchNext() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, false, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertFalse(guard.sample(Player.STATE_READY, false, true,
                TWENTY_MIN + 5000L, TWENTY_MIN, 1500L));
        assertFalse(guard.handled());
    }

    @Test public void resumeDecisionMustBeResolvedFirst() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, true, false,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertFalse(guard.sample(Player.STATE_READY, true, false,
                TWENTY_MIN + 500L, TWENTY_MIN, 2000L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 600L, TWENTY_MIN, 2200L));
        assertTrue(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 900L, TWENTY_MIN, 2600L));
    }

    @Test public void rewindingBackIntoMovieResetsNearEndWatch() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN - 10000L, TWENTY_MIN, 200L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN, TWENTY_MIN, 500L));
        assertTrue(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 100L, TWENTY_MIN, 850L));
    }

    @Test public void pauseBetweenSamplesCancelsEndDetection() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertFalse(guard.sample(Player.STATE_READY, false, true,
                TWENTY_MIN, TWENTY_MIN, 200L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN, TWENTY_MIN, 500L));
        assertTrue(guard.sample(Player.STATE_READY, true, true,
                TWENTY_MIN + 200L, TWENTY_MIN, 900L));
    }

    @Test public void idleOrTrivialDurationNeverPrompts() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        assertFalse(guard.sample(Player.STATE_IDLE, true, true,
                TWENTY_MIN, TWENTY_MIN, 100L));
        assertFalse(guard.sample(Player.STATE_IDLE, true, true,
                TWENTY_MIN + 5000L, TWENTY_MIN, 2000L));
        assertFalse(guard.sample(Player.STATE_READY, true, true,
                1000L, 500L, 2400L));
    }

    @Test public void justBeforeEndDoesNotPromptEarly() {
        PlaybackEndGuard guard = new PlaybackEndGuard();
        for (int i = 0; i < 20; i++)
            assertFalse(guard.sample(Player.STATE_READY, true, true,
                    TWENTY_MIN - 200L, TWENTY_MIN, i * 300L));
        assertFalse(guard.handled());
    }
}
