package com.nasmount.mobile;

/** A horizontal swipe changes the image only at the unzoomed fit scale. */
final class ImageSwipePolicy {
    private ImageSwipePolicy() {}

    static int direction(float dx, float dy, float widthPx, float density,
                         float zoom, boolean multiTouch) {
        if (multiTouch || zoom > 1.05f) return 0;
        float threshold = Math.max(density * 65f, widthPx * 0.12f);
        if (Math.abs(dx) < threshold || Math.abs(dx) <= Math.abs(dy) * 1.4f)
            return 0;
        return dx < 0 ? 1 : -1;
    }
}
