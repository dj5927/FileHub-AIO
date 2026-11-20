package com.nasmount.mobile;

/** Pure gesture and animation rules for the full-width photo filmstrip. */
final class ImagePagerMotionPolicy {
    private ImagePagerMotionPolicy() {}

    static boolean horizontal(float dx, float dy, float density) {
        return Math.abs(dx) >= density * 8f &&
                Math.abs(dx) > Math.abs(dy) * 1.15f;
    }

    static float offset(float dx, float width, boolean neighborReady) {
        // Never expose a black empty page while the next network photo is
        // still downloading. The current image remains fully on screen.
        if (!neighborReady || width <= 0f) return 0f;
        return Math.max(-width, Math.min(width, dx));
    }

    static float neighborOffset(float centerOffset, float width, int side) {
        // Adjacent photos stay precisely one viewport away while dragging.
        return centerOffset + side * width;
    }

    static int releaseDirection(float dx, float dy, float velocityX,
                                float width, float density, float zoom,
                                boolean multiTouch) {
        if (multiTouch || zoom > 1.05f ||
                !horizontal(dx, dy, density) || width <= 0f) return 0;
        float distance = Math.max(48f * density, width * 0.23f);
        boolean fling = Math.abs(velocityX) >= 750f * density &&
                Math.abs(dx) >= 12f * density &&
                Math.signum(velocityX) == Math.signum(dx);
        if (Math.abs(dx) < distance && !fling) return 0;
        return dx < 0f ? 1 : -1;
    }

    static long durationMs(float fromX, float toX, float width) {
        if (width <= 0f) return 200;
        float progress = Math.min(1f, Math.abs(toX - fromX) / width);
        return Math.max(130L, Math.min(280L, (long)(115f + 165f * progress)));
    }
}
