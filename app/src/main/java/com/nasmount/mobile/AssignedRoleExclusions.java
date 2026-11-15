package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Disabled child folders override inherited roles. This removes the actual
 * reader/video folder purpose, not just a shortcut in the home shelf.
 */
final class AssignedRoleExclusions {
    private static final String PREF="nasmount_folder_role_exclusions";
    private static final String KEY="scopes_v1";
    private static final String SEPARATOR="\u001f";
    private AssignedRoleExclusions(){}
    static String normalized(String kind,String path){
        if("local".equals(kind))return ReaderFolders.normalize(kind,path);
        if("storage".equals(kind))return path;
        return RemoteProvider.relativePath(path);
    }
    static String key(String mode,String kind,String owner,String path){
        return mode+SEPARATOR+kind+SEPARATOR+
                (owner==null?"":owner)+SEPARATOR+normalized(kind,path);
    }
    static Set<String> all(Context c){
        return new HashSet<>(c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                .getStringSet(KEY,new HashSet<>()));
    }
    private static void store(Context c,Set<String> scopes){
        SharedPreferences preferences=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        if(!preferences.edit().putStringSet(KEY,new HashSet<>(scopes)).commit())
            throw new IllegalStateException("제외 폴더 설정을 저장하지 못했습니다.");
    }
    static void exclude(Context c,String mode,String kind,String owner,String path){
        Set<String> scopes=all(c);
        scopes.add(key(mode,kind,owner,path));
        store(c,scopes);
    }
    static void clear(Context c,String mode,String kind,String owner,String path){
        Set<String> scopes=all(c);
        if(scopes.remove(key(mode,kind,owner,path)))store(c,scopes);
    }
    static boolean within(String ancestor,String candidate){
        return ancestor.equals(candidate)||
                (ancestor.equals("/")&&candidate.startsWith("/"))||
                (ancestor.endsWith(":")&&candidate.startsWith(ancestor))||
                candidate.startsWith(ancestor.endsWith("/")?ancestor:ancestor+"/");
    }
    static int deepestExcluded(Set<String> exclusions,String mode,String kind,
                               String owner,String path){
        String prefix=mode+SEPARATOR+kind+SEPARATOR+
                (owner==null?"":owner)+SEPARATOR;
        int depth=-1;
        for(String candidate:exclusions){
            if(!candidate.startsWith(prefix))continue;
            String parent=candidate.substring(prefix.length());
            if(within(parent,path))depth=Math.max(depth,parent.length());
        }
        return depth;
    }
    static boolean blocked(Set<String> exclusions,String mode,String kind,
                           String owner,String path,int nearestMarkLength){
        int exclusion=deepestExcluded(exclusions,mode,kind,owner,
                normalized(kind,path));
        return exclusion>=0&&exclusion>=nearestMarkLength;
    }
    static boolean bookAllowed(Context c,List<ReaderFolders.Scope> marks,
                               String mode,String kind,String owner,String path){
        String actual=normalized(kind,path);
        int nearest=-1;
        for(ReaderFolders.Scope scope:marks)
            if(scope.mode.equals(mode)&&scope.kind.equals(kind)&&
                    scope.owner.equals(owner==null?"":owner)&&
                    within(scope.path,actual))
                nearest=Math.max(nearest,scope.path.length());
        return nearest>=0&&!blocked(all(c),mode,kind,owner,actual,nearest);
    }
    static boolean videoAllowed(Context c,String folderScope,Set<String> marks){
        if(!FolderScopeFilter.folderCovered(folderScope,marks))return false;
        AssignedFolderCatalog.Folder selected=
                AssignedFolderCatalog.fromVideoScope(folderScope);
        if(selected==null)return true;
        int nearest=-1;
        for(String scope:marks){
            AssignedFolderCatalog.Folder parent=
                    AssignedFolderCatalog.fromVideoScope(scope);
            if(parent!=null&&parent.kind.equals(selected.kind)&&
                    parent.owner.equals(selected.owner)&&
                    within(parent.path,selected.path))
                nearest=Math.max(nearest,parent.path.length());
        }
        return nearest<0||!blocked(all(c),AssignedFolderCatalog.VIDEO,
                selected.kind,selected.owner,selected.path,nearest);
    }
}
