package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** Bounded stage codes only; never store account names, URLs, codes or tokens. */
final class OneDriveDiagnostics {
    private static final String PREF="filehub_onedrive_diagnostic";
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static synchronized void start(Context c){
        try{prefs(c).edit().putString("events","").apply();}catch(RuntimeException ignored){}
        mark(c,"OD01_START",null);
    }
    static String code(String stage,Throwable e){
        if(!stage.matches("OD[0-9]{2}_[A-Z_]+"))return "OD99_UNKNOWN";
        String type=e==null?"":e.getClass().getSimpleName();
        if(!type.matches("[A-Za-z0-9_$]{1,80}"))type="";
        int status=e instanceof CloudProvider.CloudFailure?((CloudProvider.CloudFailure)e).status:
            e instanceof net.openid.appauth.AuthorizationException?((net.openid.appauth.AuthorizationException)e).code:-1;
        return stage+(type.isEmpty()?"":" "+type)+(status<0?"":" "+status);
    }
    static synchronized void mark(Context c,String stage,Throwable e){
        try{
            String event=code(stage,e);
            SharedPreferences p=prefs(c);
            String old=p.getString("events","");
            String next=old+event+"\n";
            if(next.length()>3000)next=next.substring(next.length()-3000);
            p.edit().putString("events",next).apply();
            Log.i("FileHubOneDrive",event);
        }catch(RuntimeException ignored){}
    }
    static String report(Context c){
        try{return prefs(c).getString("events","");}catch(RuntimeException e){return "";}
    }
    private OneDriveDiagnostics(){}
}
