package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials never enter a plain preferences value, backup, or log. */
final class SecureStore {
    private static final String KEY_ALIAS = "NASMount_Mobile_Credentials_v1";
    private final SharedPreferences preferences;
    private final Context context;
    SecureStore(Context context) {
        this.context=context.getApplicationContext();
        preferences = context.getSharedPreferences("nasmount_settings_v1",
                Context.MODE_PRIVATE);
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS))
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }

    static String encrypt(String plain) throws Exception {
        if (plain.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] iv = c.getIV();
        byte[] bytes = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    static String decrypt(String encrypted) throws Exception {
        if (encrypted.isEmpty()) return "";
        String[] pair = encrypted.split("\\.", -1);
        if (pair.length != 2) throw new IllegalStateException("Invalid encrypted secret");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, secretKey(),
                new GCMParameterSpec(128, Base64.decode(pair[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(pair[1], Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
    }

    List<Models.RemoteProfile> remotes() throws Exception {
        JSONArray array = new JSONArray(preferences.getString("remotes", "[]"));
        List<Models.RemoteProfile> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            Models.RemoteProfile item = new Models.RemoteProfile();
            item.id = object.getString("id");
            item.name = object.optString("name", "");
            item.protocol = object.getString("protocol");
            item.host = object.getString("host");
            item.port = object.optInt("port", 0);
            item.root = object.optString("root", "");
            item.username = object.optString("username", "");
            item.ssl = object.optBoolean("ssl", true);
            item.fingerprint = object.optString("fingerprint", "");
            item.ftpExplicitTls = object.optBoolean("ftpExplicitTls", false);
            item.ftpImplicitTls = object.optBoolean("ftpImplicitTls", false);
            item.ftpPreferPasv = object.optBoolean("ftpPreferPasv", true);
            item.ftpTls12Only = object.optBoolean("ftpTls12Only", false);
            item.ftpCharset = object.optString("ftpCharset", "UTF-8");
            item.sftpCharset = object.optString("sftpCharset", "UTF-8");
            item.readOnly = object.optBoolean("readOnly", false);
            item.useLockBridge = object.optBoolean("useLockBridge", false);
            item.useNativeWebDavLocks = false; // legacy imported setting ignored
            item.officeReadTuning = object.optBoolean("officeReadTuning", false);
            item.autoConnect = object.optBoolean("autoConnect", false);
            item.windowsLetter = object.optString("windowsLetter", "");
            // Never interpret malformed/corrupted secrets as blank silently:
            // the caller must explicitly correct the account.
            item.password = decrypt(object.optString("secret", ""));
            item.nasVendor = object.optString("nasVendor", "");
            item.nasApiBase = object.optString("nasApiBase", "");
            item.nasApiUser = object.optString("nasApiUser", "");
            item.nasApiPassword = decrypt(object.optString("nasApiSecret", ""));
            item.nasDeviceId = decrypt(object.optString("nasDeviceSecret", ""));
            item.nasShareRoot = object.optString("nasShareRoot", "");
            JSONArray nasRoots = object.optJSONArray("nasShareRoots");
            if (nasRoots != null) {
                for (int j = 0; j < nasRoots.length(); j++) {
                    String path = nasRoots.optString(j, "");
                    if (!path.isEmpty() && !item.nasShareRoots.contains(path))
                        item.nasShareRoots.add(path);
                }
            } else if (!item.nasShareRoot.isEmpty()) {
                // Preserve the old single-root configuration during upgrade.
                item.nasShareRoots.add(item.nasShareRoot);
            }
            item.nasPublicHost = object.optString("nasPublicHost", "");
            item.qnapCloudSmartUrl = object.optString("qnapCloudSmartUrl", "");
            items.add(item);
        }
        return items;
    }

    /** Unsupported desktop-only clouds stay preserved but hidden on Android. */
    List<Models.RemoteProfile> supportedRemotes() throws Exception {
        List<Models.RemoteProfile> result = remotes();
        result.removeIf(p -> "GoogleDrive".equals(p.protocol) ||
                "OneDrive".equals(p.protocol));
        return result;
    }

    List<Models.RemoteProfile> activeRemotes() throws Exception {
        return MonetizationPolicy.accessible(supportedRemotes(),EditionMonetization.full(context));
    }
    boolean canSaveRemote(String id,String protocol) throws Exception {
        return MonetizationPolicy.canSave(supportedRemotes(),id,protocol,EditionMonetization.full(context));
    }
    boolean remoteAllowed(String id) throws Exception {
        return MonetizationPolicy.allowed(supportedRemotes(),id,EditionMonetization.full(context));
    }

    List<Models.Favorite> supportedFavorites() throws Exception {
        List<Models.Favorite> result = favorites();
        Set<String> retiredIds = new HashSet<>();
        for (Models.RemoteProfile profile : remotes()) {
            if ("GoogleDrive".equals(profile.protocol) ||
                    "OneDrive".equals(profile.protocol))
                retiredIds.add(profile.id);
        }
        result.removeIf(f ->
                ("remote".equals(f.kind) && retiredIds.contains(f.remoteId)) ||
                ("saf".equals(f.kind) &&
                        f.remoteId.startsWith("content://com.google.android.apps.docs")));
        return result;
    }

    List<Models.Favorite> activeFavorites() throws Exception {
        List<Models.Favorite> result=supportedFavorites();
        if(!EditionMonetization.full(context)) {
            Set<String> allowed=new HashSet<>();
            for(Models.RemoteProfile p:activeRemotes())allowed.add(p.id);
            result.removeIf(f->"remote".equals(f.kind)&&!allowed.contains(f.remoteId));
        }
        return result;
    }

    void putRemote(Models.RemoteProfile item) throws Exception {
        synchronized(SecureStore.class){
            List<Models.RemoteProfile> all=remotes();
            if(!MonetizationPolicy.canSave(all,item.id,item.protocol,EditionMonetization.full(context)))
                throw new IllegalStateException(L10n.t("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));
            // Preserve ordering so editing the free slot never changes which
            // restored profile is accessible after a downgrade.
            int index=-1;
            for(int i=0;i<all.size();i++)if(all.get(i).id.equals(item.id)){index=i;break;}
            if(index>=0)all.set(index,item);else all.add(item);
            writeRemotes(all);
        }
    }
    void deleteRemote(String id) throws Exception {
        List<Models.RemoteProfile> all = remotes();
        all.removeIf(x -> x.id.equals(id));
        writeRemotes(all);
        List<Models.Favorite> favorites = favorites();
        favorites.removeIf(x -> "remote".equals(x.kind) && id.equals(x.remoteId));
        writeFavorites(favorites);
    }
    private void writeRemotes(List<Models.RemoteProfile> items) throws Exception {
        String encoded=encodeRemotes(items);
        if (!preferences.edit().putString("remotes", encoded).commit())
            throw new IllegalStateException("Remote settings not persisted");
    }
    private static String encodeRemotes(List<Models.RemoteProfile> items) throws Exception {
        JSONArray array = new JSONArray();
        for (Models.RemoteProfile item : items) {
            JSONObject object = new JSONObject();
            object.put("id", item.id);
            object.put("name", item.name);
            object.put("protocol", item.protocol);
            object.put("host", item.host);
            object.put("port", item.port);
            object.put("root", item.root);
            object.put("username", item.username);
            object.put("ssl", item.ssl);
            object.put("fingerprint", item.fingerprint);
            object.put("ftpExplicitTls", item.ftpExplicitTls);
            object.put("ftpImplicitTls", item.ftpImplicitTls);
            object.put("ftpPreferPasv", item.ftpPreferPasv);
            object.put("ftpTls12Only", item.ftpTls12Only);
            object.put("ftpCharset", item.ftpCharset);
            object.put("sftpCharset", item.sftpCharset);
            object.put("readOnly", item.readOnly);
            object.put("useLockBridge", item.useLockBridge);
            object.put("officeReadTuning", item.officeReadTuning);
            object.put("autoConnect", item.autoConnect);
            object.put("windowsLetter", item.windowsLetter);
            object.put("secret", encrypt(item.password));
            object.put("nasVendor", item.nasVendor);
            object.put("nasApiBase", item.nasApiBase);
            object.put("nasApiUser", item.nasApiUser);
            object.put("nasApiSecret", encrypt(item.nasApiPassword));
            object.put("nasDeviceSecret", encrypt(item.nasDeviceId));
            object.put("nasShareRoot", item.nasShareRoot);
            JSONArray nasRoots = new JSONArray();
            for (String root : item.nasShareRoots) nasRoots.put(root);
            object.put("nasShareRoots", nasRoots);
            object.put("nasPublicHost", item.nasPublicHost);
            object.put("qnapCloudSmartUrl", item.qnapCloudSmartUrl);
            array.put(object);
        }
        return array.toString();
    }

    /** Commit connection and favorite snapshot together, never unencrypted. */
    int restorePortable(List<Models.RemoteProfile> imported,
                        List<Models.Favorite> incomingFavorites,boolean replace) throws Exception {
        List<Models.RemoteProfile> all=replace?new ArrayList<>():remotes();
        int changed=0;
        for(Models.RemoteProfile next:imported) {
            // Importing an existing ID intentionally updates its credentials.
            int index=-1;
            for(int i=0;i<all.size();i++)if(all.get(i).id.equals(next.id)){index=i;break;}
            if(index>=0)all.set(index,next);else all.add(next);
            changed++;
        }
        List<Models.Favorite> favorites=replace?new ArrayList<>():favorites();
        for(Models.Favorite favorite:incomingFavorites) {
            favorites.removeIf(old -> old.key().equals(favorite.key()));
            favorites.add(favorite);
        }
        String remotesJson=encodeRemotes(all);
        String favoritesJson=encodeFavorites(favorites);
        if(!preferences.edit().putString("remotes",remotesJson)
                .putString("favorites",favoritesJson).commit())
            throw new IllegalStateException("설정 동시 저장에 실패했습니다.");
        return changed;
    }

    List<Models.Favorite> favorites() throws Exception {
        return decodeFavorites(preferences.getString("favorites", "[]"));
    }
    static List<Models.Favorite> decodeFavorites(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<Models.Favorite> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            out.add(new Models.Favorite(o.getString("kind"),
                    o.getString("title"), o.getString("path"),
                    o.optString("remoteId", ""), o.optString("alias", "")));
        }
        return out;
    }
    void toggleFavorite(Models.Favorite favorite) throws Exception {
        List<Models.Favorite> all = favorites();
        boolean existed = all.removeIf(x -> x.key().equals(favorite.key()));
        if (!existed) all.add(favorite);
        writeFavorites(all);
    }
    boolean isFavorite(Models.Favorite favorite) throws Exception {
        for (Models.Favorite f : favorites()) if (f.key().equals(favorite.key())) return true;
        return false;
    }
    /** Change only the bookmark label. Actual folder name / identity and
     * location are untouched, and a missing favorite is never re-created. */
    void renameFavorite(Models.Favorite target, String proposed) throws Exception {
        String alias = FavoriteNamePolicy.normalize(proposed, target.title);
        List<Models.Favorite> all = favorites();
        boolean updated = false;
        for (Models.Favorite item : all) {
            if (item.key().equals(target.key())) {
                item.alias = alias;
                updated = true;
                break;
            }
        }
        if (!updated) throw new IllegalStateException("즐겨찾기가 존재하지 않습니다.");
        writeFavorites(all);
    }
    /** Persist drawer order, preserving hidden/retired favorites. */
    void reorderFavorites(List<String> visibleOrderedKeys) throws Exception {
        List<String> original = new ArrayList<>();
        for (Models.Favorite f : activeFavorites()) original.add(f.key());
        List<Models.Favorite> result = FavoriteOrderPolicy.reorderVisible(
                favorites(), original, visibleOrderedKeys);
        writeFavorites(result);
    }
    private void writeFavorites(List<Models.Favorite> all) throws Exception {
        if (!preferences.edit().putString("favorites", encodeFavorites(all)).commit())
            throw new IllegalStateException("Favorite not saved");
    }
    static String encodeFavorites(List<Models.Favorite> all) throws Exception {
        JSONArray array = new JSONArray();
        for (Models.Favorite favorite : all) {
            JSONObject o = new JSONObject();
            o.put("kind", favorite.kind);
            o.put("title", favorite.title);
            o.put("alias", favorite.alias == null ? "" : favorite.alias);
            o.put("path", favorite.path);
            o.put("remoteId", favorite.remoteId);
            array.put(o);
        }
        return array.toString();
    }

    String sdTreeUri() {
        return preferences.getString("sdTreeUri", "");
    }
    void sdTreeUri(String uri) {
        preferences.edit().putString("sdTreeUri", uri).apply();
    }
    String selectedTreeUri() {
        return preferences.getString("selectedTreeUri", "");
    }
    void selectedTreeUri(String uri) {
        preferences.edit().putString("selectedTreeUri", uri).apply();
    }
}
