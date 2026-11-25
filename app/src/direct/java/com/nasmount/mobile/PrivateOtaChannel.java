package com.nasmount.mobile;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Optional private OTA; all credentials are provisioned per user, never embedded. */
final class PrivateOtaChannel {
    private static final String PREFS="filehub_private_ota";
    final String base,token;
    PrivateOtaChannel(String endpoint,String token) throws Exception {
        URI uri=new URI(endpoint.trim());
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null ||
                uri.getRawUserInfo()!=null || uri.getRawQuery()!=null ||
                uri.getRawFragment()!=null || !uri.normalize().equals(uri) ||
                (uri.getRawPath()!=null && !uri.getRawPath().matches("[A-Za-z0-9_./-]*")))
            throw new IllegalArgumentException("HTTPS update server URL required");
        if(token==null||!token.matches("[A-Za-z0-9_-]{32,128}"))
            throw new IllegalArgumentException("Invalid access code");
        String normalized=uri.toASCIIString();
        this.base=normalized.endsWith("/")?normalized:normalized+"/";
        this.token=token;
    }
    static PrivateOtaChannel load(Context c)throws Exception {
        android.content.SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        if(!p.getBoolean("enabled",false))return null;
        return new PrivateOtaChannel(p.getString("base",""),
                SecureStore.decrypt(p.getString("token","")));
    }
    static void save(Context c,String base,String token)throws Exception {
        PrivateOtaChannel config=new PrivateOtaChannel(base,token);
        if(!c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString("base",config.base).putString("token",SecureStore.encrypt(config.token))
                .putBoolean("enabled",true).commit())throw new IOException("Cannot save update configuration");
    }
    static void clear(Context c) {
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();
    }
    static boolean enabled(Context c) {
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean("enabled",false);
    }
    static String endpoint(Context c) {
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString("base","");
    }
    HttpURLConnection open(String url)throws IOException {
        // Exactly scoped same-origin paths. Never forward the bearer to a
        // redirect, CDN, public GitHub or URL supplied by release metadata.
        if(!url.equals(base+"manifest.json") &&
                !url.matches(java.util.regex.Pattern.quote(base)+"download/FileHub-AIO-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk"))
            throw new SecurityException("Untrusted private OTA URL");
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(6500);conn.setReadTimeout(25000);
        conn.setRequestProperty("Authorization","Bearer "+token);
        conn.setRequestProperty("User-Agent","FileHub-AIO-Android-OTA");
        return conn;
    }
    OtaReleasePolicy.Release parse(JSONObject json)throws Exception {
        if(!"direct".equals(json.getString("channel")) ||
                !"com.nasmount.mobile".equals(json.getString("package")))
            throw new SecurityException("Unexpected update channel");
        String version=json.getString("versionName");
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("Invalid version");
        int code=json.getInt("versionCode");
        long size=json.getLong("size");
        String checksum=json.getString("sha256");
        if(code<1||size<100000||size>500000000L||!checksum.matches("[a-fA-F0-9]{64}"))
            throw new IOException("Invalid private OTA metadata");
        String filename="FileHub-AIO-v"+version+".apk";
        if(!filename.equals(json.getString("filename")))throw new IOException("Invalid APK filename");
        return new OtaReleasePolicy.Release(version,code,json.optString("notes",""),
                base+"download/"+filename,checksum.toLowerCase(Locale.ROOT),size);
    }
    HttpURLConnection openDownload(OtaReleasePolicy.Release release)throws IOException {
        String expected=base+"download/FileHub-AIO-v"+release.version+".apk";
        if(!expected.equals(release.url))throw new SecurityException("Update channel changed; check again");
        return open(expected);
    }
}
