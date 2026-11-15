package com.nasmount.mobile;

/** Pure decision policy so existing videos do not resume at the ending. */
final class PlaybackResumePolicy {
    static final long MINIMUM_POSITION_MS = 10000;
    private PlaybackResumePolicy() {}

    static boolean canResume(long position, long duration) {
        if (position < MINIMUM_POSITION_MS) return false;
        if (duration <= 0) return true;
        long finishMargin = Math.min(30000, Math.max(5000, duration / 20));
        return position < duration - finishMargin;
    }
}
