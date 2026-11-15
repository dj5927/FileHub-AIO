package com.nasmount.mobile;

/**
 * Video orientation comes from the decoded VIDEO dimensions, not the screen
 * or media filename. Rotation and non-square pixel aspect are considered.
 */
final class VideoOrientationPolicy {
    private VideoOrientationPolicy() {}

    /** Returns null if dimensions are not yet known. */
    static Boolean isLandscape(int width, int height, int unappliedRotationDegrees,
                               float pixelWidthHeightRatio) {
        if (width <= 0 || height <= 0) return null;
        double aspect = Float.isFinite(pixelWidthHeightRatio) &&
                pixelWidthHeightRatio > 0 ? pixelWidthHeightRatio : 1.0;
        double displayWidth = width * aspect;
        double displayHeight = height;
        if (((unappliedRotationDegrees % 360) + 360) % 180 == 90) {
            double swap = displayWidth;
            displayWidth = displayHeight;
            displayHeight = swap;
        }
        // Square videos stay portrait; treat only actual wide content as landscape.
        return displayWidth > displayHeight * 1.08;
    }
}
