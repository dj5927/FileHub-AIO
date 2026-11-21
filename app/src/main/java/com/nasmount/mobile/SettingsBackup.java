package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Portable snapshot: decrypts device-bound credentials only in memory. */
final class SettingsBackup {
    static final String KIND="filehub-portable-settings";
    static final int VERSION=1;
    // Portable configuration ONLY. Playback and reading state is intentionally
    // private to each Android device and must never be sent to another device,
    // even if an older .fhbackup contains these preference files.
    private static final String[] FILES={
            "appearance", "file_view", "drawer_sections", "nas_video_settings",
            "nasmount_reader_folders", "nasmount_folder_role_exclusions",
            "folder_reader_playback_overrides_v1", "comic_reader_preferences",
            "epub_book_settings", "text_book_reader_settings", "reader_shared_page_turn"
    };

    static boolean transfersPreferenceFile(String name) {
        for (String file : FILES) if (file.equals(name)) return true;
        return false;
    }

    static boolean isDeviceOnlyState(String name) {
        switch (name) {
            case "video_watch_progress":
            case "nasmount_reading_history_v1":
            case "comic_reader_progress":
            case "text_book_reader_progress":
            case "large_txt_read_positions":
            case "comic_logical_page_migration":
            case "video_subtitle_inventory":
                return true;
            default:
                return false;
        }
    }
    private SettingsBackup(){}

    static JSONObject snapshot(Context context,SecureStore store) throws Exception {
        JSONObject root=new JSONObject();
        root.put("kind",KIND);
        root.put("version",VERSION);
        root.put("created",System.currentTimeMillis());
        JSONArray remotes=new JSONArray();
        for(Models.RemoteProfile profile:store.activeRemotes())
            remotes.put(profileToJson(profile));
        root.put("remotes",remotes);
        root.put("favorites",new JSONArray(SecureStore.encodeFavorites(
                store.activeFavorites())));
        JSONObject settings=new JSONObject();
        for(String name:FILES) {
            SharedPreferences pref=context.getSharedPreferences(name,Context.MODE_PRIVATE);
            JSONObject entries=new JSONObject();
            for(Map.Entry<String,?> e:pref.getAll().entrySet()) {
                String key=e.getKey();
                // Content URI permissions and codec files belong to one device;
                // Android does not grant them to a second installation.
                if(key==null || key.startsWith("codec_tree") ||
                        key.toLowerCase(java.util.Locale.ROOT).contains("permission_uri"))
                    continue;
                Object value=e.getValue();
                JSONObject entry=encodePreference(value);
                if(entry!=null)entries.put(key,entry);
            }
            settings.put(name,entries);
        }
        root.put("preferences",settings);
        return root;
    }

    static JSONObject profileToJson(Models.RemoteProfile p) throws Exception {
        JSONObject o=new JSONObject();
        o.put("id",p.id);o.put("name",p.name);o.put("protocol",p.protocol);
        o.put("host",p.host);o.put("port",p.port);o.put("root",p.root);
        o.put("username",p.username);o.put("password",p.password);
        o.put("ssl",p.ssl);o.put("fingerprint",p.fingerprint);
        o.put("ftpExplicitTls",p.ftpExplicitTls);
        o.put("ftpImplicitTls",p.ftpImplicitTls);
        o.put("ftpPreferPasv",p.ftpPreferPasv);
        o.put("ftpTls12Only",p.ftpTls12Only);
        o.put("ftpCharset",p.ftpCharset);
        o.put("sftpCharset",p.sftpCharset);
        o.put("readOnly",p.readOnly);
        o.put("useLockBridge",p.useLockBridge);
        o.put("useNativeWebDavLocks",p.useNativeWebDavLocks);
        o.put("officeReadTuning",p.officeReadTuning);
        o.put("autoConnect",p.autoConnect);
        o.put("windowsLetter",p.windowsLetter);
        o.put("nasVendor",p.nasVendor);o.put("nasApiBase",p.nasApiBase);
        o.put("nasApiUser",p.nasApiUser);o.put("nasApiPassword",p.nasApiPassword);
        o.put("nasShareRoot",p.nasShareRoot);
        o.put("nasShareRoots",new JSONArray(p.nasShareRoots));
        o.put("nasPublicHost",p.nasPublicHost);
        o.put("qnapCloudSmartUrl",p.qnapCloudSmartUrl);
        // DSM's trust token is paired with the *original* phone; never export.
        return o;
    }

    static Models.RemoteProfile profileFromJson(JSONObject o) throws Exception {
        Models.RemoteProfile p=new Models.RemoteProfile();
        String id=o.optString("id","");
        if(id.length()<8 || id.length()>100)
            throw new IllegalArgumentException("잘못된 원격 연결 ID입니다.");
        p.id=id;
        p.name=o.optString("name","");p.protocol=o.getString("protocol");
        if("MyBox".equals(p.protocol))p.protocol="MYBOX";
        if(!("SMB".equals(p.protocol)||"FTP".equals(p.protocol)||
                "SFTP".equals(p.protocol)||"WebDAV".equals(p.protocol)||
                "MYBOX".equals(p.protocol)||"TeraBox".equals(p.protocol)||
                "GoogleDrive".equals(p.protocol)))
            throw new IllegalArgumentException("지원하지 않는 연결 프로토콜입니다.");
        p.host=o.optString("host","");p.port=o.optInt("port",0);
        p.root=o.optString("root","");p.username=o.optString("username","");
        p.password=o.optString("password","");p.ssl=o.optBoolean("ssl",true);
        p.fingerprint=o.optString("fingerprint","");
        p.ftpExplicitTls=o.optBoolean("ftpExplicitTls",false);
        p.ftpImplicitTls=o.optBoolean("ftpImplicitTls",false);
        p.ftpPreferPasv=o.optBoolean("ftpPreferPasv",true);
        p.ftpTls12Only=o.optBoolean("ftpTls12Only",false);
        p.ftpCharset=o.optString("ftpCharset","UTF-8");
        p.sftpCharset=o.optString("sftpCharset","UTF-8");
        p.readOnly=o.optBoolean("readOnly",false);
        p.useLockBridge=o.optBoolean("useLockBridge",false);
        p.useNativeWebDavLocks=o.optBoolean("useNativeWebDavLocks",false);
        p.officeReadTuning=o.optBoolean("officeReadTuning",false);
        p.autoConnect=o.optBoolean("autoConnect",false);
        p.windowsLetter=o.optString("windowsLetter","");
        p.nasVendor=o.optString("nasVendor","");p.nasApiBase=o.optString("nasApiBase","");
        p.nasApiUser=o.optString("nasApiUser","");
        p.nasApiPassword=o.optString("nasApiPassword","");
        p.nasShareRoot=o.optString("nasShareRoot","");
        JSONArray roots=o.optJSONArray("nasShareRoots");
        if(roots!=null)for(int i=0;i<roots.length();i++) {
            String path=roots.getString(i);
            if(!p.nasShareRoots.contains(path))p.nasShareRoots.add(path);
        }
        p.nasPublicHost=o.optString("nasPublicHost","");
        p.qnapCloudSmartUrl=o.optString("qnapCloudSmartUrl","");
        return p;
    }

    private static JSONObject encodePreference(Object value) throws Exception {
        if(value==null)return null;
        JSONObject o=new JSONObject();
        if(value instanceof String){o.put("t","s");o.put("v",value);}
        else if(value instanceof Integer){o.put("t","i");o.put("v",value);}
        else if(value instanceof Long){o.put("t","l");o.put("v",value);}
        else if(value instanceof Float){o.put("t","f");o.put("v",value);}
        else if(value instanceof Boolean){o.put("t","b");o.put("v",value);}
        else if(value instanceof Set) {
            JSONArray values=new JSONArray();
            for(Object element:(Set<?>)value) {
                if(!(element instanceof String))return null;
                values.put(element);
            }
            o.put("t","ss");o.put("v",values);
        }else return null;
        return o;
    }

    static void validate(JSONObject root) throws Exception {
        if(!KIND.equals(root.optString("kind")) || root.optInt("version",-1)!=VERSION)
            throw new IllegalArgumentException("지원하지 않는 FileHub 백업 파일입니다.");
        JSONArray remotes=root.getJSONArray("remotes");
        JSONArray favorites=root.getJSONArray("favorites");
        if(remotes.length()>100 || favorites.length()>10_000)
            throw new IllegalArgumentException("백업 항목 수가 너무 많습니다.");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<remotes.length();i++) {
            JSONObject entry=remotes.getJSONObject(i);
            if("GoogleDrive".equals(entry.optString("protocol")) ||
                    "OneDrive".equals(entry.optString("protocol")))continue;
            Models.RemoteProfile p=profileFromJson(entry);
            if(!ids.add(p.id))throw new IllegalArgumentException("중복된 연결 ID가 있습니다.");
        }
        SecureStore.decodeFavorites(favorites.toString());
        JSONObject prefs=root.getJSONObject("preferences");
        for(String file:FILES) {
            JSONObject entries=prefs.optJSONObject(file);
            if(entries==null)continue;
            if(entries.length()>10_000)
                throw new IllegalArgumentException("설정 항목이 너무 많습니다.");
            java.util.Iterator<String> keys=entries.keys();
            while(keys.hasNext()) {
                String key=keys.next();
                if(key.isEmpty() || key.length()>2048)throw new IllegalArgumentException("설정 키 오류");
                decodePreference(entries.getJSONObject(key),null,key);
            }
        }
    }

    /** Restoring profiles and favorites is an atomic SecureStore write. */
    static int restore(Context context,SecureStore store,JSONObject root,
                       boolean replace) throws Exception {
        validate(root);
        JSONArray incoming=root.getJSONArray("remotes");
        List<Models.RemoteProfile> profiles=new ArrayList<>();
        for(int i=0;i<incoming.length();i++) {
            JSONObject entry=incoming.getJSONObject(i);
            if("GoogleDrive".equals(entry.optString("protocol")) ||
                    "OneDrive".equals(entry.optString("protocol")))continue;
            profiles.add(profileFromJson(entry));
        }
        List<Models.Favorite> favorites=SecureStore.decodeFavorites(
                root.getJSONArray("favorites").toString());
        JSONObject prefs=root.getJSONObject("preferences");
        // Pre-validate all values before touching the user's current settings.
        for(String file:FILES){
            JSONObject entries=prefs.optJSONObject(file);
            if(entries==null)continue;
            java.util.Iterator<String> it=entries.keys();
            while(it.hasNext()) {
                String key=it.next();
                decodePreference(entries.getJSONObject(key),null,key);
            }
        }
        int restored=store.restorePortable(profiles,favorites,replace);
        for(String file:FILES) {
            JSONObject entries=prefs.optJSONObject(file);
            if(entries==null)continue;
            SharedPreferences.Editor edit=context.getSharedPreferences(
                    file,Context.MODE_PRIVATE).edit();
            if(replace)edit.clear();
            java.util.Iterator<String> it=entries.keys();
            while(it.hasNext()){
                String key=it.next();
                if(key.startsWith("codec_tree") ||
                        key.toLowerCase(java.util.Locale.ROOT).contains("permission_uri"))continue;
                decodePreference(entries.getJSONObject(key),edit,key);
            }
            if(!edit.commit())throw new IllegalStateException("설정 저장 실패: " + file);
        }
        return restored;
    }

    private static void decodePreference(JSONObject entry,SharedPreferences.Editor edit,
                                         String key) throws Exception {
        switch(entry.getString("t")){
            case "s":{String v=entry.getString("v");if(edit!=null)edit.putString(key,v);break;}
            case "i":{int v=entry.getInt("v");if(edit!=null)edit.putInt(key,v);break;}
            case "l":{long v=entry.getLong("v");if(edit!=null)edit.putLong(key,v);break;}
            case "f":{float v=(float)entry.getDouble("v");if(edit!=null)edit.putFloat(key,v);break;}
            case "b":{boolean v=entry.getBoolean("v");if(edit!=null)edit.putBoolean(key,v);break;}
            case "ss":{
                JSONArray data=entry.getJSONArray("v");
                if(data.length()>10_000)throw new IllegalArgumentException("설정 목록이 너무 큽니다.");
                Set<String> vals=new HashSet<>();
                for(int i=0;i<data.length();i++)vals.add(data.getString(i));
                if(edit!=null)edit.putStringSet(key,vals);
                break;
            }
            default:throw new IllegalArgumentException("알 수 없는 설정 유형입니다.");
        }
    }
}
