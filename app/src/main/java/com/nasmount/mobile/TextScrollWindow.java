package com.nasmount.mobile;

/** Bounding a TextView to a small segment avoids multi-megabyte
 * android.text.Layout construction and main-thread freezes. */
final class TextScrollWindow {
    static final int SPAN=24000;
    static final int BACKTRACK=6000;
    static final int EDGE=2300;
    private TextScrollWindow(){}
    static int start(String text,int offset) {
        int pos=Math.max(0,Math.min(text.length(),offset));
        int start=Math.max(0,pos-BACKTRACK);
        if(start>0 && Character.isLowSurrogate(text.charAt(start)))
            start--;
        return start;
    }
    static int end(String text,int start) {
        int end=Math.min(text.length(),start+SPAN);
        if(end<text.length() && end>start &&
                Character.isHighSurrogate(text.charAt(end-1)))end--;
        return end;
    }
    static boolean shift(String text,int start,int end,int firstVisible,int lastVisible) {
        return (firstVisible-start<EDGE && start>0) ||
                (end-lastVisible<EDGE && end<text.length());
    }
}
