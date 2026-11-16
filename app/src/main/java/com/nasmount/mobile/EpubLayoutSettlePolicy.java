package com.nasmount.mobile;

/**
 * WebView.onPageFinished and the first visual-state callback do not mean
 * asynchronous EPUB fonts/images have completed their multi-column reflow.
 * This policy watches the actual scroll extent and viewport until stable.
 */
final class EpubLayoutSettlePolicy {
    // The older 1250/500ms hard holds were visible as a chapter-turn freeze.
    // The separate end-position guard already follows any *late* font reflow.
    // Only wait for several consistent initial viewport/content observations.
    static final int POLL_MS=70;
    static final int STABLE_SAMPLES=3;
    static final int END_MIN_WAIT_MS=300;
    static final int OTHER_MIN_WAIT_MS=160;
    static final int MAX_WAIT_MS=1800;

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
