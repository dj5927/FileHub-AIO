package com.nasmount.mobile;

/** Exact two-page starts, with an optional blank right leaf at the end. */
final class PdfSpreadPolicy {
    private PdfSpreadPolicy(){}
    static boolean active(boolean foldPreference,int availableWidthDp){
        return foldPreference&&availableWidthDp>=600;
    }
    static int start(int requested,int pageCount,boolean spread){
        int clamped=PdfPageSizing.page(requested,pageCount);
        return spread?(clamped/2)*2:clamped;
    }
    static int next(int current,int direction,int pageCount,boolean spread){
        return start(current,pageCount,spread)+(direction<0?-1:1)*(spread?2:1);
    }
    static int lastVisible(int start,int pageCount,boolean spread){
        return Math.min(pageCount,start+(spread?2:1));
    }
    static String pageLabel(int start,int pageCount,boolean spread){
        int end=lastVisible(start,pageCount,spread);
        return (spread&&end>start+1?(start+1)+"–"+end:
                String.valueOf(start+1))+" / "+pageCount+"쪽";
    }
}
