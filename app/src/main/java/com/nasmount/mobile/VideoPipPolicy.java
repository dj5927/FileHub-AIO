package com.nasmount.mobile;

/** Testable rules for Android system picture-in-picture, without activity state. */
final class VideoPipPolicy {
    private VideoPipPolicy(){}

    static boolean canOffer(boolean supported,boolean tv,boolean ended){
        return supported&&!tv&&!ended;
    }

    static boolean autoEnter(boolean userOptedIn,boolean supported,
                             boolean tv,boolean isPlaying,boolean ended){
        return userOptedIn&&canOffer(supported,tv,ended)&&isPlaying;
    }
    static boolean releaseWhenStopped(boolean everEnteredPip,boolean finishing){
        return everEnteredPip||finishing;
    }

    /**
     * Android PiP accepts [1:2.39, 2.39:1]. Video dimensions are used in
     * preference to the Activity's portrait/landscape orientation.
     */
    static int[] aspect(int width,int height){
        if(width<1||height<1)return new int[]{16,9};
        double ratio=(double)width/height;
        if(ratio>2.39d)return new int[]{239,100};
        if(ratio<1d/2.39d)return new int[]{100,239};
        return new int[]{width,height};
    }
}
