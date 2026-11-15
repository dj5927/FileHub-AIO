package com.nasmount.mobile;

import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Comic volumes from one visible folder only. The queue contains no credentials. */
final class BookSeriesQueue {
    static final String EXTRA="comic_book_series_v1";
    static final int MAX_VOLUMES=500;
    static final class Volume {
        final String name,path,uri;
        Volume(String name,String path,String uri){
            this.name=name;this.path=path;this.uri=uri;
        }
        String location(){return uri!=null?uri:path;}
    }
    private BookSeriesQueue(){}
    static String encode(List<Volume> volumes) throws Exception {
        JSONArray array=new JSONArray();
        for(Volume v:volumes) {
            if(array.length()>=MAX_VOLUMES)break;
            JSONObject o=new JSONObject();
            o.put("name",v.name);
            if(v.path!=null)o.put("path",v.path);
            if(v.uri!=null)o.put("uri",v.uri);
            array.put(o);
        }
        return array.toString();
    }
    static List<Volume> decode(String value) {
        List<Volume> out=new ArrayList<>();
        if(value==null)return out;
        try {
            JSONArray array=new JSONArray(value);
            for(int i=0;i<array.length()&&i<MAX_VOLUMES;i++){
                JSONObject o=array.getJSONObject(i);
                String name=o.optString("name","");
                if(!ComicArchive.supports(name))continue;
                String path=o.optString("path",null);
                String uri=o.optString("uri",null);
                if(path==null && uri==null)continue;
                out.add(new Volume(name,path,uri));
            }
        }catch(Exception invalid){out.clear();}
        out.sort(Comparator.comparing((Volume v)->v.name,
                ComicArchive::naturalCompare));
        return out;
    }
    static Volume next(List<Volume> books,String name,String location) {
        for(int i=0;i<books.size()-1;i++){
            Volume current=books.get(i);
            if(current.name.equals(name)&&current.location().equals(location))
                return sameSeries(current.name,books.get(i+1).name)?
                        books.get(i+1):null;
        }
        return null;
    }
    static boolean sameSeries(String a,String b){
        return prefix(a).equals(prefix(b));
    }
    private static String prefix(String name){
        String text=name.replaceFirst("(?i)\\.(zip|cbz|7z|cb7|rar|cbr)$","")
                .replaceFirst("(?i)\\s*\\d+(?:\\s*(?:권|편|화|vol\\.?))?\\s*$","")
                .toLowerCase(Locale.ROOT);
        return text.replaceAll("[\\s_\\-]+","");
    }
    static Intent nextIntent(Intent current,Volume next){
        Intent intent=new Intent(current);
        intent.putExtra(ReaderSource.NAME,next.name);
        if(next.path!=null){
            intent.putExtra(ReaderSource.PATH,next.path);
            intent.removeExtra(ReaderSource.URI);
        }else{
            intent.putExtra(ReaderSource.URI,next.uri);
            intent.removeExtra(ReaderSource.PATH);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }
}
