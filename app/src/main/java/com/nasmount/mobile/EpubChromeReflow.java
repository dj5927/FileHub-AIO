package com.nasmount.mobile;

/**
 * When EPUB toolbar height changes, CSS columns repaginate.
 * The restored offset MUST be aligned to a complete viewport page.
 * A raw fraction produces a screen containing the tail of one page
 * and the beginning of the next page.
 */
final class EpubChromeReflow {
    private EpubChromeReflow(){}

    static int horizontalSnap(int progressOutOf9999,int extent,int viewport){
        int fraction=Math.max(0,Math.min(9999,progressOutOf9999));
        int raw=Math.round(Math.max(0,extent)*fraction/9999f);
        return EpubPaginationPolicy.restoreNearest(raw,viewport,extent);
    }

    static int verticalRestore(int progressOutOf9999,int extent){
        int fraction=Math.max(0,Math.min(9999,progressOutOf9999));
        return Math.round(Math.max(0,extent)*fraction/9999f);
    }
}
