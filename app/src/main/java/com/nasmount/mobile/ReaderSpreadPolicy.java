package com.nasmount.mobile;

/** Deterministic page grouping on unfolded / tablet-sized displays. */
final class ReaderSpreadPolicy {
    private ReaderSpreadPolicy(){}
    static boolean comic(int screenWidthDp,boolean paired,boolean fitWidth){
        return screenWidthDp>=600&&paired&&!fitWidth;
    }
    static boolean text(int screenWidthDp,boolean paired,int mode){
        return screenWidthDp>=600&&paired&&mode==0;
    }
    static int start(int page,boolean paired){
        int safe=Math.max(0,page);
        return paired?(safe/2)*2:safe;
    }
    static int end(int start,int total,boolean paired){
        return Math.min(total,start+(paired?2:1));
    }
}
