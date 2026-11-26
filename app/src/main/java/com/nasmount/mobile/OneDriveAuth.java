package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import net.openid.appauth.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
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
    static Uri redirect(Context c){return Uri.parse(c.getPackageName()+":/oauth2redirect");}
    static AuthorizationRequest request(Context c,String hint){
        AuthorizationRequest.Builder b=new AuthorizationRequest.Builder(config(),BuildConfig.ONEDRIVE_CLIENT_ID,
            ResponseTypeValues.CODE,redirect(c)).setScope(SCOPES);
        if(hint==null||hint.isEmpty())b.setPrompt("select_account");else b.setLoginHint(hint);
        return b.build(); // AppAuth generates cryptographically random state and S256 PKCE.
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
    static synchronized String token(Models.RemoteProfile p)throws Exception{
        AuthState state=load(p);
        if(!state.getNeedsTokenRefresh()&&state.getAccessToken()!=null&&!state.getAccessToken().isEmpty())
            return state.getAccessToken();
        CountDownLatch latch=new CountDownLatch(1);String[] value={null};
        AuthorizationService service=new AuthorizationService(app);
        try{
            state.performActionWithFreshTokens(service,(access,id,error)->{
                if(error==null)value[0]=access;latch.countDown();
            });
            if(!latch.await(35,TimeUnit.SECONDS)||value[0]==null||value[0].isEmpty())throw required();
            remember(p,state);return value[0];
        }finally{service.dispose();}
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
