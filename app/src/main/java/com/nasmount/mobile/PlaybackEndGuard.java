package com.nasmount.mobile;

import androidx.media3.common.C;
import androidx.media3.common.Player;

/**
 * End-of-media fallback for codecs/sources whose text renderer or stream
 * continues to advance after the reported media duration. In that situation
 * Player.STATE_ENDED can arrive far later than the video itself finishes.
 *
 * Requires two consecutive near-end samples, spaced in real time, and an
 * actual user-initiated playing state. Does not trigger on paused seeking,
 * loading a video, unknown duration or resume prompts.
 */
final class PlaybackEndGuard {
    static final long MIN_DURATION_MS = 1000L;
    static final long NEAR_END_TOLERANCE_MS = 120L;
    static final long CONFIRM_WINDOW_MS = 300L;
    private long nearEndSinceMs = -1L;
    private boolean handled;

    boolean handled() { return handled; }

    boolean sample(int state, boolean playWhenReady, boolean resumeConfirmed,
                   long positionMs, long durationMs, long elapsedRealtimeMs) {
        if (handled) return false;
        if (!resumeConfirmed) {
            nearEndSinceMs = -1L;
            return false;
        }
        if (state == Player.STATE_ENDED) {
            handled = true;
            return true;
        }
        if (!playWhenReady ||
                (state != Player.STATE_READY && state != Player.STATE_BUFFERING) ||
                durationMs == C.TIME_UNSET || durationMs < MIN_DURATION_MS ||
                positionMs < 0 || positionMs == C.TIME_UNSET ||
                positionMs < durationMs - NEAR_END_TOLERANCE_MS) {
            nearEndSinceMs = -1L;
            return false;
        }
        if (nearEndSinceMs < 0 || elapsedRealtimeMs < nearEndSinceMs) {
            nearEndSinceMs = elapsedRealtimeMs;
            return false;
        }
        if (elapsedRealtimeMs - nearEndSinceMs < CONFIRM_WINDOW_MS) return false;
        handled = true;
        return true;
    }
}
