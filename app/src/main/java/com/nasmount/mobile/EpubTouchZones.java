package com.nasmount.mobile;

/**
 * EPUB reading gestures: short taps use the left/centre/right screen zones;
 * horizontal swipes retain the existing page-turn behavior. Vertical drags,
 * multi-touch and long presses must be left to WebView.
 */
final class EpubTouchZones {
    static final int NONE=0;
    static final int PREVIOUS=1;
    static final int NEXT=2;
    static final int TOGGLE_BARS=3;
    static final int SWIPE_PREVIOUS=4;
    static final int SWIPE_NEXT=5;
    private EpubTouchZones(){}

    static int classify(float downX,float downY,float upX,float upY,
                        int width,long durationMs,float tapSlop,
                        float swipeThreshold,boolean paged,boolean multiTouch){
        if(width<=0||durationMs<0||multiTouch)return NONE;
        float dx=upX-downX;
        float dy=upY-downY;
        float move=Math.max(Math.abs(dx),Math.abs(dy));
        if(durationMs<=450 && move<=tapSlop){
            float fraction=upX/width;
            if(fraction<0.30f)return PREVIOUS;
            if(fraction>0.70f)return NEXT;
            return TOGGLE_BARS;
        }
        if(paged && Math.abs(dx)>=swipeThreshold &&
                Math.abs(dx)>Math.abs(dy)*1.5f){
            return dx<0?SWIPE_NEXT:SWIPE_PREVIOUS;
        }
        return NONE;
    }
}
