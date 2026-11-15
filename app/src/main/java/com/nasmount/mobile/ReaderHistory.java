package com.nasmount.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared read-only book state and user bookmarks for archive / TXT readers.
 * Keys include storage type and remote account/SAF URI to avoid collisions. */
final class ReaderHistory {
    private static final String PREFS="nasmount_reading_history_v1";
    private ReaderHistory(){}

    static String id(String kind,String profile,String path,String uri) {
        String plain=(kind==null?"":kind)+"\u0000"+
                (profile==null?"":profile)+"\u0000"+
                (path==null?"":path)+"\u0000"+(uri==null?"":uri);
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(
                    plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(digest.length*2);
            final char[] hex="0123456789abcdef".toCharArray();
            for(byte b:digest){
                out.append(hex[(b>>>4)&15]);
                out.append(hex[b&15]);
            }
            return out.toString();
        }catch(Exception unavailable){throw new IllegalStateException(unavailable);}
    }
    static String id(Intent intent){
        return id(intent.getStringExtra(ReaderSource.KIND),
                intent.getStringExtra(ReaderSource.PROFILE),
                intent.getStringExtra(ReaderSource.PATH),
                intent.getStringExtra(ReaderSource.URI));
    }
    static int percent(Context context,String id) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getInt("percent_"+id,-1);
    }
    static int position(Context context,String id,int fallback) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getInt("position_"+id,fallback);
    }
    static void save(Context context,String id,int position,int readUnits,int totalUnits) {
        if(totalUnits<=0||position<0)return;
        int percent=percentFromUnits(readUnits,totalUnits);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putInt("position_"+id,position)
                .putInt("percent_"+id,percent).apply();
    }
    static int percentFromUnits(int readUnits,int totalUnits) {
        if(totalUnits<=0)return 0;
        return Math.max(0,Math.min(100,Math.round(
                100f*Math.max(0,Math.min(totalUnits,readUnits))/totalUnits)));
    }
    static List<Integer> decode(String json) {
        List<Integer> result=new ArrayList<>();
        try {
            JSONArray arr=new JSONArray(json);
            for(int i=0;i<arr.length()&&i<100;i++){
                int point=arr.getInt(i);
                if(point>=0&&!result.contains(point))result.add(point);
            }
        }catch(Exception invalid){return Collections.emptyList();}
        Collections.sort(result);
        return result;
    }
    static List<Integer> bookmarks(Context context,String id) {
        return decode(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
                .getString("bookmarks_"+id,"[]"));
    }
    static void setBookmark(Context context,String id,int position,boolean keep) {
        if(position<0)return;
        List<Integer> points=new ArrayList<>(bookmarks(context,id));
        points.remove(Integer.valueOf(position));
        if(keep && points.size()<100)points.add(position);
        Collections.sort(points);
        JSONArray array=new JSONArray();
        for(int point:points)array.put(point);
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString("bookmarks_"+id,array.toString()).apply();
    }
    static int migratedComicPosition(Context context,String newId,String oldId) {
        return position(context,newId,
                context.getSharedPreferences("comic_reader_progress",Context.MODE_PRIVATE)
                        .getInt("page_"+oldId,0));
    }
    static int migratedTextPosition(Context context,String newId,String oldId) {
        return position(context,newId,
                context.getSharedPreferences("text_book_reader_progress",Context.MODE_PRIVATE)
                        .getInt("offset_"+oldId,0));
    }
}
