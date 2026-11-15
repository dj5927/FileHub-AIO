package com.nasmount.mobile;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Per-directory reader/player overrides layered over legacy global defaults. */
final class FolderMediaSettings {
    static final String VIDEO="video",COMIC="comic",TEXT="text",EPUB="epub";
    private static final String STORE="folder_reader_playback_overrides_v1";
    private FolderMediaSettings(){}

    static String reader(Intent source){
        if(source==null)return "";
        // SAF and Downloads providers can use opaque document IDs instead
        // of hierarchical paths; the browser knows the real parent folder.
        String explicit=source.getStringExtra(ReaderSource.FOLDER_SCOPE);
        if(explicit!=null&&!explicit.isEmpty())return explicit;
        String kind=source.getStringExtra(ReaderSource.KIND);
        if("remote".equals(kind))return FolderSettingsPolicy.scope(
                "remote",source.getStringExtra(ReaderSource.PROFILE),
                source.getStringExtra(ReaderSource.PATH));
        if("local".equals(kind))return FolderSettingsPolicy.scope(
                "local","",source.getStringExtra(ReaderSource.PATH));
        if("saf".equals(kind)){
            String raw=source.getStringExtra(ReaderSource.URI);
            if(raw==null)return "";
            Uri uri=Uri.parse(raw);
            try{return FolderSettingsPolicy.scope("saf",uri.getAuthority(),
                    DocumentsContract.getDocumentId(uri));}
            catch(Exception ignored){
                return FolderSettingsPolicy.scope("saf",uri.getAuthority(),
                        Uri.decode(uri.getLastPathSegment()));
            }
        }
        return "";
    }

    static String video(Context context,Intent source){
        if(source==null)return "";
        // Keep launch extras independent of the Media3 @UnstableApi player
        // class, which otherwise propagates experimental-API lint errors.
        // The file manager knows the actual parent directory even when a
        // Downloads/MediaStore content URI contains only an opaque file ID.
        String explicit=source.getStringExtra("sourceFolderScope");
        if(explicit!=null&&!explicit.isEmpty())return explicit;
        String profile=source.getStringExtra("profileId");
        if(profile!=null)return FolderSettingsPolicy.scope("remote",profile,
                source.getStringExtra("remotePath"));
        String raw=source.getStringExtra("localUri");
        if(raw==null)return "";
        Uri uri=Uri.parse(raw);
        if("file".equalsIgnoreCase(uri.getScheme()))
            return FolderSettingsPolicy.scope("local","",uri.getPath());
        if("content".equalsIgnoreCase(uri.getScheme())){
            try{
                if(DocumentsContract.isDocumentUri(context,uri))
                    return FolderSettingsPolicy.scope("saf",uri.getAuthority(),
                            DocumentsContract.getDocumentId(uri));
            }catch(Exception ignored){}
            try(Cursor cursor=context.getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.RELATIVE_PATH},
                    null,null,null)){
                if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0)){
                    String directory=cursor.getString(0);
                    return FolderSettingsPolicy.scope("media",
                            uri.getAuthority(),"/"+directory+"/playing.file");
                }
            }catch(Exception ignored){}
        }
        return "";
    }
    private static String prefKey(String category,String scope,String name){
        String group=FolderSettingsPolicy.group(category,scope);
        if(group.isEmpty()||name==null||name.isEmpty())return "";
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
                    group.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder("folder_");
            for(int i=0;i<16;i++)
                hex.append(Character.forDigit((bytes[i]>>>4)&15,16))
                        .append(Character.forDigit(bytes[i]&15,16));
            return hex+"_"+name;
        }catch(Exception error){return "";}
    }
    private static SharedPreferences storage(Context context){
        return context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
    }
    static int integer(Context c,String category,String scope,
                       String key,int fallback){
        String name=prefKey(category,scope,key);
        return name.isEmpty()?fallback:storage(c).getInt(name,fallback);
    }
    static boolean bool(Context c,String category,String scope,
                        String key,boolean fallback){
        String name=prefKey(category,scope,key);
        return name.isEmpty()?fallback:storage(c).getBoolean(name,fallback);
    }
    static String string(Context c,String category,String scope,
                         String key,String fallback){
        String name=prefKey(category,scope,key);
        return name.isEmpty()?fallback:storage(c).getString(name,fallback);
    }
    static boolean contains(Context c,String category,String scope,String key){
        String name=prefKey(category,scope,key);
        return !name.isEmpty()&&storage(c).contains(name);
    }
    static void integerSet(Context c,String category,String scope,
                           String key,int value){
        String name=prefKey(category,scope,key);
        if(!name.isEmpty())storage(c).edit().putInt(name,value).apply();
    }
    static void boolSet(Context c,String category,String scope,
                        String key,boolean value){
        String name=prefKey(category,scope,key);
        if(!name.isEmpty())storage(c).edit().putBoolean(name,value).apply();
    }
    static void stringSet(Context c,String category,String scope,
                          String key,String value){
        String name=prefKey(category,scope,key);
        if(!name.isEmpty())storage(c).edit()
                .putString(name,value==null?"":value).apply();
    }
    static void remove(Context c,String category,String scope,String key){
        String name=prefKey(category,scope,key);
        if(!name.isEmpty())storage(c).edit().remove(name).apply();
    }
    static void clear(Context c,String category,String scope,String...keys){
        SharedPreferences.Editor edit=storage(c).edit();
        boolean changed=false;
        for(String key:keys){
            String name=prefKey(category,scope,key);
            if(!name.isEmpty()){edit.remove(name);changed=true;}
        }
        if(changed)edit.apply();
    }
}
