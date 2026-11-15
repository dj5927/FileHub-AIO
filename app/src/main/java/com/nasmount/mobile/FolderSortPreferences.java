package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Exact-directory sort overrides. A directory override never changes the
 * global sort order and never cascades into its child directories.
 *
 * Scope is supplied by MainActivity.folderScope(), which distinguishes local,
 * SAF document IDs and remote storage profiles.
 */
final class FolderSortPreferences {
    private static final String STORE="file_view_folder_sort_v1";
    private FolderSortPreferences(){}

    static String key(String scope){
        if(scope==null||scope.isEmpty())return "";
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(
                    scope.getBytes(StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder("sort_");
            for(int i=0;i<16;i++){
                key.append(Character.forDigit((digest[i]>>>4)&15,16));
                key.append(Character.forDigit(digest[i]&15,16));
            }
            return key.toString();
        }catch(Exception error){throw new IllegalStateException(error);}
    }

    private static SharedPreferences store(Context context){
        return context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
    }
    static boolean has(Context context,String scope){
        String key=key(scope);
        return !key.isEmpty()&&store(context).contains(key);
    }
    static int resolve(int globalSort,Integer override){
        if(override!=null&&override>=FileViewOptions.NAME_ASC&&
                override<=FileViewOptions.SIZE_SMALL)return override;
        return globalSort;
    }
    static int get(Context context,String scope,int globalSort){
        String key=key(scope);
        if(key.isEmpty()||!store(context).contains(key))return globalSort;
        return resolve(globalSort,store(context).getInt(key,globalSort));
    }
    static void set(Context context,String scope,int sorting){
        String key=key(scope);
        if(key.isEmpty()||sorting<FileViewOptions.NAME_ASC||
                sorting>FileViewOptions.SIZE_SMALL)
            throw new IllegalArgumentException("폴더 정렬 조건이 올바르지 않습니다.");
        store(context).edit().putInt(key,sorting).apply();
    }
    static void clear(Context context,String scope){
        String key=key(scope);
        if(!key.isEmpty())store(context).edit().remove(key).apply();
    }
}
