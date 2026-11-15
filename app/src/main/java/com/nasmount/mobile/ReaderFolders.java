package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit book-only folder scopes. General ZIP/TXT behavior is not changed. */
final class ReaderFolders {
    static final String COMIC="comic";
    static final String TEXT="text";
    private static final String KEY="book_folder_scopes_v1";
    private final SharedPreferences prefs;
    private final Context context;
    ReaderFolders(Context context) {
        this.context=context.getApplicationContext();
        prefs=context.getSharedPreferences("nasmount_reader_folders",Context.MODE_PRIVATE);
    }
    static final class Scope {
        final String mode,kind,owner,path;
        Scope(String mode,String kind,String owner,String path){
            this.mode=mode;this.kind=kind;this.owner=owner==null?"":owner;
            this.path=normalize(kind,path);
        }
        boolean matches(String testKind,String testOwner,String folderPath){
            if(!modeValid(mode)||!kind.equals(testKind)||
                    !owner.equals(testOwner==null?"":testOwner))return false;
            String folder=normalize(testKind,folderPath);
            return path.equals("/") || folder.equals(path) ||
                    folder.startsWith(path+"/");
        }
    }
    static boolean modeValid(String mode){
        return COMIC.equals(mode)||TEXT.equals(mode);
    }
    static String normalize(String kind,String path) {
        if("local".equals(kind)) {
            if(path==null||!path.startsWith("/"))throw new IllegalArgumentException("절대 경로 필요");
            String value=path.replace('\\','/');
            while(value.length()>1&&value.endsWith("/"))value=value.substring(0,value.length()-1);
            return value;
        }
        return RemoteProvider.relativePath(path);
    }
    static boolean contains(List<Scope> scopes,String mode,String kind,
                            String owner,String folder) {
        for(Scope scope:scopes)
            if(scope.mode.equals(mode)&&scope.matches(kind,owner,folder))return true;
        return false;
    }
    static List<Scope> decode(String raw)throws Exception{
        JSONArray arr=new JSONArray(raw==null?"[]":raw);
        List<Scope> result=new ArrayList<>();
        for(int i=0;i<arr.length();i++){
            JSONObject o=arr.getJSONObject(i);
            String kind=o.optString("kind","");
            String mode=o.optString("mode","");
            if(!modeValid(mode)||!(kind.equals("local")||kind.equals("saf")||
                    kind.equals("remote")))continue;
            result.add(new Scope(mode,kind,o.optString("owner",""),
                    o.getString("path")));
        }
        return result;
    }
    static String encode(List<Scope> scopes)throws Exception{
        JSONArray arr=new JSONArray();
        for(Scope s:scopes){
            JSONObject o=new JSONObject();
            o.put("mode",s.mode);o.put("kind",s.kind);
            o.put("owner",s.owner);o.put("path",s.path);
            arr.put(o);
        }
        return arr.toString();
    }
    List<Scope> all()throws Exception{return decode(prefs.getString(KEY,"[]"));}
    boolean allowed(String mode,String kind,String owner,String path){
        try{return AssignedRoleExclusions.bookAllowed(context,all(),mode,kind,owner,path);}
        catch(Exception error){return false;}
    }
    boolean exact(String mode,String kind,String owner,String path){
        try{
            String normalized=normalize(kind,path);
            for(Scope s:all())if(s.mode.equals(mode)&&s.kind.equals(kind)&&
                    s.owner.equals(owner==null?"":owner)&&s.path.equals(normalized))return true;
        }catch(Exception error){return false;}
        return false;
    }
    boolean toggle(String mode,String kind,String owner,String path)throws Exception{
        if(!modeValid(mode))throw new IllegalArgumentException("지원되지 않는 책 유형");
        Scope candidate=new Scope(mode,kind,owner,path);
        List<Scope> scopes=all();
        boolean removed=scopes.removeIf(s->s.mode.equals(mode)&&s.kind.equals(kind)&&
                s.owner.equals(candidate.owner)&&s.path.equals(candidate.path));
        if(!removed)scopes.add(candidate);
        if(!prefs.edit().putString(KEY,encode(scopes)).commit())
            throw new IllegalStateException("책 폴더 설정을 저장하지 못했습니다.");
        if(!removed)AssignedRoleExclusions.clear(context,mode,kind,owner,path);
        return !removed;
    }
    boolean removeExact(String mode,String kind,String owner,String path)throws Exception{
        Scope candidate=new Scope(mode,kind,owner,path);
        List<Scope> scopes=all();
        boolean removed=scopes.removeIf(s->s.mode.equals(mode)&&
                s.kind.equals(kind)&&s.owner.equals(candidate.owner)&&
                s.path.equals(candidate.path));
        if(removed&&!prefs.edit().putString(KEY,encode(scopes)).commit())
            throw new IllegalStateException("책 폴더 설정을 해제하지 못했습니다.");
        return removed;
    }
}
