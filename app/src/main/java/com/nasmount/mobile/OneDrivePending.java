package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import net.openid.appauth.AuthorizationRequest;
import org.json.JSONObject;
import java.io.IOException;

/** Only one active account-link transaction. The PKCE verifier is Keystore encrypted. */
final class OneDrivePending {
    final AuthorizationRequest request;
    final String id,name;
    final boolean readOnly;
    final long created;
    OneDrivePending(AuthorizationRequest r,String i,String n,boolean ro,long t){
        request=r;id=i;name=n;readOnly=ro;created=t;
    }
    private static SharedPreferences prefs(Context c){
        return c.getApplicationContext().getSharedPreferences("filehub_onedrive_pending_v2",Context.MODE_PRIVATE);
    }
    static synchronized void save(Context c,OneDrivePending p)throws Exception{
        JSONObject o=new JSONObject().put("request",p.request.jsonSerializeString())
            .put("id",p.id==null?JSONObject.NULL:p.id).put("name",p.name)
            .put("readOnly",p.readOnly).put("created",p.created);
        if(!prefs(c).edit().putString("pending",SecureStore.encrypt(o.toString())).commit())
            throw new IOException("OneDrive pending write failed");
    }
    private static OneDrivePending read(Context c)throws Exception{
        String raw=prefs(c).getString("pending","");
        if(raw.isEmpty())throw new IOException("No OneDrive authorization pending");
        JSONObject o=new JSONObject(SecureStore.decrypt(raw));
        AuthorizationRequest r=AuthorizationRequest.jsonDeserialize(o.getString("request"));
        if(!BuildConfig.ONEDRIVE_CLIENT_ID.equals(r.clientId)||!OneDriveAuth.redirect(c).equals(r.redirectUri)||
            !OneDriveAuth.config().authorizationEndpoint.equals(r.configuration.authorizationEndpoint)||
            !OneDriveAuth.config().tokenEndpoint.equals(r.configuration.tokenEndpoint)||
            r.codeVerifier==null||r.state==null)throw new IOException("Invalid OneDrive pending request");
        return new OneDrivePending(r,o.isNull("id")?null:o.getString("id"),
            o.getString("name"),o.getBoolean("readOnly"),o.getLong("created"));
    }
    static synchronized OneDrivePending consume(Context c,OneDriveCallbackPolicy.Result callback)throws Exception{
        OneDrivePending p=read(c);
        if(!OneDriveCallbackPolicy.matches(p.request.state,callback.state,p.created,System.currentTimeMillis()))
            throw new IOException("OneDrive callback state mismatch or expired");
        // Consume before code exchange; replay or duplicate delivery cannot create a second profile.
        if(!prefs(c).edit().remove("pending").commit())throw new IOException("OneDrive pending consume failed");
        return p;
    }
    static synchronized void cancel(Context c,String state){
        if(state==null)return;
        try{
            OneDrivePending p=read(c);
            if(state.equals(p.request.state))prefs(c).edit().remove("pending").commit();
        }catch(Exception ignored){}
    }
    private OneDrivePending(){throw new AssertionError();}
}
