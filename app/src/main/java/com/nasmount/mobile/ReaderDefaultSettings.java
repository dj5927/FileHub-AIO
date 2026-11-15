package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Global defaults. In-viewer edits are stored separately per folder. */
final class ReaderDefaultSettings {
    private ReaderDefaultSettings(){}
    static SharedPreferences prefs(Context context,String category){
        String name;
        if(FolderMediaSettings.COMIC.equals(category))
            name="comic_reader_preferences";
        else if(FolderMediaSettings.EPUB.equals(category))
            name="epub_book_settings";
        else name="text_book_reader_settings";
        return context.getSharedPreferences(name,Context.MODE_PRIVATE);
    }
    static int integer(Context context,String category,String key,int fallback){
        return prefs(context,category).getInt(key,fallback);
    }
    static boolean bool(Context context,String category,String key,boolean fallback){
        return prefs(context,category).getBoolean(key,fallback);
    }
    static int turn(Context context,String category){
        String key=FolderMediaSettings.EPUB.equals(category)?
                "page_turn_effect":"turn_effect";
        return Math.max(0,Math.min(3,
                integer(context,category,key,ReaderTurnPreferences.style(context))));
    }
    static boolean slow(Context context,String category){
        return bool(context,category,"slow_page_turn",
                ReaderTurnPreferences.slow(context));
    }
    static void setTurn(Context context,String category,int style,boolean slow){
        String key=FolderMediaSettings.EPUB.equals(category)?
                "page_turn_effect":"turn_effect";
        prefs(context,category).edit()
                .putInt(key,Math.max(0,Math.min(3,style)))
                .putBoolean("slow_page_turn",slow).apply();
    }
}
