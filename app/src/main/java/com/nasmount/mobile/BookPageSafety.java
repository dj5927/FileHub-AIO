package com.nasmount.mobile;

/**
 * StaticLayout and TextView can differ by a fractional line after applying
 * device font metrics and line spacing. Never paginate all the way to the
 * physical bottom; keep over two full lines of breathing room, independent
 * of font size, spacing or a dual-page Fold layout.
 */
final class BookPageSafety {
    private BookPageSafety(){}
    static int usableHeight(int contentHeight,float fontLineHeight,
                            float lineSpacing,int extraPixels){
        if(contentHeight<=0)return 0;
        float safeLine=Math.max(1f,fontLineHeight)*
                Math.max(1f,lineSpacing);
        // On Korean phrase wrapping, a real TextView can gain another
        // visual row compared with its measured StaticLayout on Android's
        // vendor fonts. Reserve over TWO complete text rows. The previous
        // 1.3 rows still allowed a partially clipped last line on Fold.
        int reserved=Math.max(1,(int)Math.ceil(safeLine*2.35f))+
                Math.max(0,extraPixels);
        // Keep adequate room on very small previews and floating windows.
        int minimum=Math.min(contentHeight,Math.max(32,
                (int)Math.ceil(safeLine*1.3f)));
        return Math.max(minimum,contentHeight-reserved);
    }
}
