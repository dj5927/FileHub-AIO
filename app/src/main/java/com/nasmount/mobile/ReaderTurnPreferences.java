package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** One page-turn style and speed shared by EPUB, TXT and comic readers. */
final class ReaderTurnPreferences {
    static final String FILE="reader_shared_page_turn";
    static final String STYLE="style";
    static final String SLOW="slow";
    static final String[] STYLES={
        "종이책 모서리 말림 (실시간 드래그)","부드럽게 슬라이드",
        "부드럽게 페이드","효과 없음"
    };
    static final String[] SPEEDS={"자연스럽게","천천히"};
    private ReaderTurnPreferences(){}
    static int toIndex(int effect){
        switch(effect){
            case EpubTurnGeometry.CURL:return 0;
            case EpubTurnGeometry.SLIDE:return 1;
            case EpubTurnGeometry.FADE:return 2;
            default:return 3;
        }
    }
    static int fromIndex(int index){
        switch(index){
            case 0:return EpubTurnGeometry.CURL;
            case 1:return EpubTurnGeometry.SLIDE;
            case 2:return EpubTurnGeometry.FADE;
            default:return EpubTurnGeometry.NONE;
        }
    }
    static SharedPreferences shared(Context context){
        return context.getSharedPreferences(FILE,Context.MODE_PRIVATE);
    }
    static int style(Context context){
        SharedPreferences common=shared(context);
        if(common.contains(STYLE))
            return Math.max(0,Math.min(3,common.getInt(STYLE,3)));
        // Migrate the user's existing EPUB preference from v0.1.50. Default
        // Paper Curl is intentionally enabled for all three readers.
        return Math.max(0,Math.min(3,context.getSharedPreferences(
                "epub_book_settings",Context.MODE_PRIVATE)
                .getInt("page_turn_effect",EpubTurnGeometry.CURL)));
    }
    static boolean slow(Context context){
        SharedPreferences common=shared(context);
        if(common.contains(SLOW))return common.getBoolean(SLOW,false);
        return context.getSharedPreferences("epub_book_settings",
                Context.MODE_PRIVATE).getBoolean("slow_page_turn",false);
    }
    static void save(Context context,int style,boolean slow){
        shared(context).edit()
                .putInt(STYLE,Math.max(0,Math.min(3,style)))
                .putBoolean(SLOW,slow).apply();
    }
}
