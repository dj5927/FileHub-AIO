package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Looper;
import net.openid.appauth.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Publisher-owned public OAuth client, browser-based authorization code + PKCE.
 * Device-specific OAuth state is encrypted with Android Keystore, outside backups.
 */
final class OneDriveAuth {
    static final String PROTOCOL="OneDrive", HOST="graph.microsoft.com";
    static final String SCOPES="https://graph.microsoft.com/Files.ReadWrite https://graph.microsoft.com/User.Read offline_access";
    static AuthorizationServiceConfiguration config(){return new AuthorizationServiceConfiguration(
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
        Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"));}
    private static volatile Context app;
    static void init(Context c){app=c.getApplicationContext();}
    static boolean configured(){return !BuildConfig.ONEDRIVE_CLIENT_ID.isEmpty();}
    static String redirectForPackage(String packageName){
        return packageName+"://oauth2redirect";
    }
    static Uri redirect(Context c){return Uri.parse(redirectForPackage(c.getPackageName()));}
    static AuthorizationRequest request(Context c,String hint){
        AuthorizationRequest.Builder b=new AuthorizationRequest.Builder(config(),BuildConfig.ONEDRIVE_CLIENT_ID,
            ResponseTypeValues.CODE,redirect(c)).setScope(SCOPES).setResponseMode("query");
        // This method is called only for explicit add/reconnect, not routine
        // token refresh. Request the official consent screen even if a prior
        // attempt granted access but never created a FileHub profile.
        b.setPrompt("consent");
        if(hint!=null&&!hint.isEmpty())b.setLoginHint(hint);
        return b.build(); // AppAuth generates cryptographically random state and S256 PKCE.
    }
    private static final okhttp3.OkHttpClient TOKEN_HTTP=new okhttp3.OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS)
        .writeTimeout(15,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS).build();
    static final class TokenFailure extends IOException {
        final int status;
        TokenFailure(int s){super("OneDrive token exchange failed");status=s;}
    }
    // The AppAuth library is used only for its PKCE and state models. No
    // browser binding, AsyncTask or synchronous main-looper dependency.
    static TokenResponse exchange(TokenRequest request)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("OneDrive token on UI thread");
        if(!BuildConfig.ONEDRIVE_CLIENT_ID.equals(request.clientId)||
            !config().tokenEndpoint.equals(request.configuration.tokenEndpoint))
            throw new IOException("OneDrive token endpoint mismatch");
        okhttp3.FormBody.Builder form=new okhttp3.FormBody.Builder().add("client_id",request.clientId);
        for(java.util.Map.Entry<String,String> entry:request.getRequestParameters().entrySet())
            if(!"client_id".equals(entry.getKey()))form.add(entry.getKey(),entry.getValue());
        okhttp3.Request http=new okhttp3.Request.Builder().url(config().tokenEndpoint.toString())
            .post(form.build()).header("Accept","application/json").build();
        try(okhttp3.Response r=TOKEN_HTTP.newCall(http).execute()){
            if(!r.isSuccessful()||r.body()==null)throw new TokenFailure(r.code());
            // Microsoft responses are small. Do not log response bodies or URL.
            TokenResponse result=new TokenResponse.Builder(request).fromResponseJsonString(r.body().string()).build();
            if(result.accessToken==null||result.accessToken.isEmpty()||
                !"Bearer".equalsIgnoreCase(result.tokenType)||result.accessTokenExpirationTime==null)
                throw new IOException("OneDrive invalid token response");
            return result;
        }
    }
    static IOException required(){return new IOException(L10n.t("OneDrive 로그인이 필요합니다. 연결 설정에서 다시 로그인하세요."));}
    static boolean linked(Models.RemoteProfile p){
        return PROTOCOL.equals(p.protocol)&&HOST.equals(p.host)&&!p.username.isEmpty()&&p.password.isEmpty()&&
            p.fingerprint.startsWith("onedrive:")&&p.fingerprint.length()>9;
    }
    static java.io.File tempFile()throws IOException{
        if(app==null)throw required();return java.io.File.createTempFile("onedrive-upload-",".tmp",app.getCacheDir());
    }
    static String marker(String userId,String driveId){
        return "onedrive:"+userId+":"+driveId;
    }
    private static SharedPreferences prefs(){return app.getSharedPreferences("filehub_onedrive_oauth_v1",Context.MODE_PRIVATE);}
    private static String key(Models.RemoteProfile p){return "state_"+p.id;}
    static synchronized void remember(Models.RemoteProfile p,AuthState state)throws Exception{
        if(!linked(p)||!state.isAuthorized()||state.getRefreshToken()==null)throw required();
        String value=new org.json.JSONObject().put("identity",p.fingerprint).put("client",BuildConfig.ONEDRIVE_CLIENT_ID)
            .put("state",state.jsonSerializeString()).put("refresh",state.getNeedsTokenRefresh()).toString();
        if(!prefs().edit().putString(key(p),SecureStore.encrypt(value)).commit())throw new IOException("OneDrive credential save failed");
    }
    private static AuthState load(Models.RemoteProfile p)throws Exception{
        if(!configured()||app==null||!linked(p))throw required();
        String stored=prefs().getString(key(p),"");
        if(stored.isEmpty())throw required();
        org.json.JSONObject value=new org.json.JSONObject(SecureStore.decrypt(stored));
        if(!p.fingerprint.equals(value.optString("identity"))||!BuildConfig.ONEDRIVE_CLIENT_ID.equals(value.optString("client")))throw required();
        AuthState state=AuthState.jsonDeserialize(value.getString("state"));
        if(value.optBoolean("refresh"))state.setNeedsTokenRefresh(true);
        return state;
    }
    // Save encrypted credentials first, verify they can be read, then
    // publish the profile. Restore the old credential if profile saving fails.
    static synchronized void commitLink(SecureStore store,Models.RemoteProfile p,AuthState state)throws Exception{
        String previous=prefs().getString(key(p),null);
        try{
            remember(p,state);
            AuthState verified=load(p);
            if(!verified.isAuthorized()||verified.getRefreshToken()==null)throw required();
            store.putRemote(p);
        }catch(Exception|LinkageError e){
            SharedPreferences.Editor edit=prefs().edit();
            if(previous==null)edit.remove(key(p));else edit.putString(key(p),previous);
            edit.commit();
            throw e;
        }
    }
    private static final java.util.concurrent.ConcurrentHashMap<String,Object> refreshLocks=new java.util.concurrent.ConcurrentHashMap<>();
    static String token(Models.RemoteProfile p)throws Exception{
        // Never block the UI waiting for a file worker's refresh lock.
        if(Looper.myLooper()==Looper.getMainLooper())throw required();
        synchronized(refreshLocks.computeIfAbsent(p.id,k->new Object())){
            return workerToken(p);
        }
    }
    private static String workerToken(Models.RemoteProfile p)throws Exception{
        final AuthState state;
        final String snapshot;
        synchronized(OneDriveAuth.class){
            state=load(p);snapshot=prefs().getString(key(p),"");
        }
        if(!state.getNeedsTokenRefresh()&&state.getAccessToken()!=null&&!state.getAccessToken().isEmpty())
            return state.getAccessToken();
        try{
            TokenResponse refreshed=exchange(state.createTokenRefreshRequest());
            state.update(refreshed,null);state.setNeedsTokenRefresh(false);
            synchronized(OneDriveAuth.class){
                if(!snapshot.equals(prefs().getString(key(p),"")))throw required();
                remember(p,state);
            }
            return state.getAccessToken();
        }catch(Exception e){
            OneDriveDiagnostics.mark(app,"OD27_TOKEN_REFRESH",e);
            throw e;
        }
    }
    static synchronized void invalidate(Models.RemoteProfile p,String rejected){
        try{
            AuthState state=load(p);
            if(rejected.equals(state.getAccessToken())){state.setNeedsTokenRefresh(true);remember(p,state);}
        }catch(Exception ignored){}
    }
    static synchronized void forget(Models.RemoteProfile p){
        if(app!=null)prefs().edit().remove(key(p)).commit();
    }
    private OneDriveAuth(){}
}
