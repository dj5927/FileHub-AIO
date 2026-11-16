package com.nasmount.mobile;

/**
 * WebView.onPageFinished and the first visual-state callback do not mean
 * asynchronous EPUB fonts/images have completed their multi-column reflow.
 * This policy watches the actual scroll extent and viewport until stable.
 */
final class EpubLayoutSettlePolicy {
    static final int POLL_MS=100;
    static final int STABLE_SAMPLES=5;
    static final int END_MIN_WAIT_MS=1250;
    static final int OTHER_MIN_WAIT_MS=500;
    static final int MAX_WAIT_MS=3500;

    private int lastExtent=-1,lastViewport=-1,stableSamples;
    private EpubLayoutSettlePolicy(){}

    static EpubLayoutSettlePolicy create(){return new EpubLayoutSettlePolicy();}

    boolean observe(int extent,int viewport,long elapsedMs,
                    boolean previousChapterEnd){
        if(viewport<=0)return false;
        if(lastExtent!=extent||lastViewport!=viewport){
            lastExtent=extent;
            lastViewport=viewport;
            stableSamples=1;
        }else stableSamples++;
        int minimum=previousChapterEnd?
                END_MIN_WAIT_MS:OTHER_MIN_WAIT_MS;
        return elapsedMs>=MAX_WAIT_MS||
                (elapsedMs>=minimum&&stableSamples>=STABLE_SAMPLES);
    }
    int lastExtent(){return lastExtent;}
    int viewport(){return lastViewport;}
    int stableSamples(){return stableSamples;}
}
