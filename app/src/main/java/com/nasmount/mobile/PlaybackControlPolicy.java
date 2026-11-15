package com.nasmount.mobile;

/** Integer tenths keep each 0.1x speed change exact and reversible. */
final class PlaybackControlPolicy {
    static final int MIN_SPEED_TENTHS = 5;
    static final int MAX_SPEED_TENTHS = 30;
    static final int DEFAULT_SPEED_TENTHS = 10;

    static final int AUTO = 0;
    static final int LANDSCAPE = 1;
    static final int PORTRAIT = 2;

    private PlaybackControlPolicy() {}

    static int adjustSpeed(int tenths, int delta) {
        return Math.max(MIN_SPEED_TENTHS,
                Math.min(MAX_SPEED_TENTHS, tenths + delta));
    }

    static int toTenths(float speed) {
        return adjustSpeed(Math.round(speed * 10), 0);
    }

    static float toSpeed(int tenths) {
        return adjustSpeed(tenths, 0) / 10.0f;
    }

    static String speedLabel(int tenths) {
        return String.format(java.util.Locale.ROOT,
                "%.1f×", toSpeed(tenths));
    }

    static int nextOrientation(int current) {
        return current == AUTO ? LANDSCAPE :
                current == LANDSCAPE ? PORTRAIT : AUTO;
    }

    static String orientationLabel(int mode) {
        return mode == LANDSCAPE ? "가로" :
                mode == PORTRAIT ? "세로" : "자동";
    }
}
