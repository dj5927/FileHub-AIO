package com.nasmount.mobile;

/**
 * Pixel sizes for a TYPE_APPLICATION_OVERLAY confirmation window. Unlike a
 * dialog themed from the video Activity, these derive from the actual phone
 * display, not the Activity's PiP bounds or its landscape window metrics.
 */
final class PipOverlaySizing {
    private PipOverlaySizing(){}

    static int widthPx(int physicalDisplayWidthPx,int densityBasedMarginPx,
                       int maxCardWidthPx){
        return Math.min(Math.max(1,maxCardWidthPx),
                Math.max(1,physicalDisplayWidthPx-
                        Math.max(0,densityBasedMarginPx)*2));
    }
}
