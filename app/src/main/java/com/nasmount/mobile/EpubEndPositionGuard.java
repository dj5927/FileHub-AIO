package com.nasmount.mobile;

/**
 * A cross-chapter PREVIOUS action means "last page of the prior chapter".
 * Keep that semantic destination through late font and viewport reflows,
 * then stop following it as soon as the reader manually navigates.
 */
final class EpubEndPositionGuard {
    private int chapter=-1,lastExtent=-1,lastViewport=-1;
    void pin(int chapter,int viewport,int extent){
        this.chapter=chapter;
        lastViewport=viewport;
        lastExtent=extent;
    }
    void clear(){chapter=-1;lastExtent=-1;lastViewport=-1;}
    boolean activeFor(int chapter){return this.chapter==chapter&&chapter>=0;}
    boolean observe(int chapter,int viewport,int extent){
        if(!activeFor(chapter)||viewport<=0)return false;
        if(lastExtent==extent&&lastViewport==viewport)return false;
        lastExtent=extent;
        lastViewport=viewport;
        return true;
    }
}
