package com.nasmount.mobile;

/**
 * Read the nearby pages first; whole-book pagination is a background-only
 * index. Keep the current page near the middle of the text window so a user
 * can page in either direction immediately. Long addresses are safe for
 * multi-gigabyte TXT files.
 */
final class TextPageWindow {
    private TextPageWindow(){}
    static long point(long requested,long total){
        if(total<=0)return 0;
        return Math.max(0,Math.min(total-1,requested));
    }
    static long base(long requested,long total,boolean nextStartsHere){
        long selected=point(requested,total);
        return nextStartsHere?selected:Math.max(0,selected-6000);
    }
    static int span(long base,long total){
        return (int)Math.max(0,Math.min(48000,total-Math.max(0,base)));
    }
}
