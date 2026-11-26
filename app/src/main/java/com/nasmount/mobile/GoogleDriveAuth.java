package com.nasmount.mobile;

import android.accounts.Account;
import android.content.Context;
import android.os.SystemClock;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Google Play Services owns consent and token refresh. Access tokens are memory-only. */
final class GoogleDriveAuth {
    static final String PROTOCOL="GoogleDrive", HOST="drive.google.com";
    static final String SCOPE="https://www.googleapis.com/auth/drive";
    private static final String MARKER="gdrive:";
    private static volatile Context app;
    private static final Map<String,Session> sessions=new HashMap<>();
    private static final class Session {
        final String token; final long expiry;
        Session(String t){token=t;expiry=SystemClock.elapsedRealtime()+5*60*1000L;}
    }
    static void init(Context c){app=c.getApplicationContext();}
    static boolean linked(Models.RemoteProfile p){
        return PROTOCOL.equals(p.protocol)&&!p.username.isEmpty()&&p.password.isEmpty()&&
            HOST.equals(p.host)&&p.fingerprint.startsWith(MARKER)&&p.fingerprint.length()>MARKER.length();
    }
    static AuthorizationRequest request(String account){
        return AuthorizationRequest.builder()
            .setAccount(new Account(account,"com.google"))
            .setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build();
    }
    static String granted(AuthorizationResult r)throws IOException {
        if(r==null||r.hasResolution()||r.getAccessToken()==null||r.getAccessToken().isEmpty()||
            r.getGrantedScopes()==null||!r.getGrantedScopes().contains(SCOPE))throw required();
        return r.getAccessToken();
    }
    static IOException required(){return new IOException(L10n.t("Google Drive 로그인이 필요합니다. 연결 설정에서 다시 로그인하세요."));}
    static java.io.File tempFile()throws IOException {
        if(app==null)throw required();return java.io.File.createTempFile("gdrive-upload-",".tmp",app.getCacheDir());
    }
    static String marker(String permissionId){return MARKER+permissionId;}
    private static String key(Models.RemoteProfile p){return p.username+"\n"+p.fingerprint;}
    static synchronized void remember(Models.RemoteProfile p,String token){
        if(linked(p))sessions.put(key(p),new Session(token));
    }
    static synchronized String token(Models.RemoteProfile p)throws Exception {
        if(!linked(p)||app==null)throw required();
        Session session=sessions.get(key(p));
        if(session!=null&&session.expiry>SystemClock.elapsedRealtime())return session.token;
        try{
            AuthorizationResult r=Tasks.await(Identity.getAuthorizationClient(app).authorize(request(p.username)),25,TimeUnit.SECONDS);
            String token=granted(r);
            GoogleDriveApi.Identity user=GoogleDriveApi.identity(token);
            if(!p.username.equalsIgnoreCase(user.email)||!p.fingerprint.equals(marker(user.permissionId)))throw required();
            remember(p,token);return token;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        catch(Exception e){throw required();}
    }
    static synchronized void forget(Models.RemoteProfile p){sessions.remove(key(p));}
    // Revoke only the chosen Google account and the requested Drive scope.
    // Never revoke on token refresh, app launch, or ordinary existing-account connection.
    static void revokeDriveGrant(String account)throws Exception{
        if(app==null||account==null||account.isEmpty())throw required();
        forgetAccount(account);
        try{
            Tasks.await(Identity.getAuthorizationClient(app).revokeAccess(
                RevokeAccessRequest.builder().setAccount(new Account(account,"com.google"))
                    .setScopes(Collections.singletonList(new Scope(SCOPE))).build()),30,TimeUnit.SECONDS);
        }finally{forgetAccount(account);}
    }
    private static synchronized void forgetAccount(String account){
        sessions.keySet().removeIf(k->{
            int split=k.indexOf('\n');
            return split>=0&&k.substring(0,split).equalsIgnoreCase(account);
        });
    }
    static synchronized void invalidate(Models.RemoteProfile p,String rejected){
        Session session=sessions.get(key(p));
        if(session!=null&&session.token.equals(rejected))sessions.remove(key(p));
        if(app!=null&&rejected!=null&&!rejected.isEmpty())try{
            Tasks.await(Identity.getAuthorizationClient(app).clearToken(
                ClearTokenRequest.builder().setToken(rejected).build()),10,TimeUnit.SECONDS);
        }catch(Exception ignored){}
    }
    private GoogleDriveAuth(){}
}
