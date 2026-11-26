package com.nasmount.mobile;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Browser-only entry point. No token exchange, disk decryption or network on the UI thread. */
public final class OneDriveRedirectActivity extends Activity {
    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        Intent incoming=getIntent();
        try{
            if(incoming!=null&&Intent.ACTION_VIEW.equals(incoming.getAction())&&incoming.getData()!=null){
                String raw=incoming.getData().toString();
                OneDriveDiagnostics.mark(this,"OD34_REDIRECT_RECEIVED",null);
                if(!OneDriveCallbackPolicy.matchesEndpoint(new java.net.URI(raw),
                        new java.net.URI(OneDriveAuth.redirectForPackage(getPackageName())))){
                    OneDriveDiagnostics.mark(this,"OD35_REDIRECT_INVALID",null);return;
                }
                // Let the connection screen report malformed callback data;
                // never silently discard a callback to our exact endpoint.

                Intent resume=new Intent(this,OneDriveConnectActivity.class)
                    .setAction(OneDriveConnectActivity.CALLBACK)
                    .setData(incoming.getData())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(resume);
            }
        }catch(Exception e){OneDriveDiagnostics.mark(this,"OD35_REDIRECT_INVALID",e);}
        finally{
            if(incoming!=null)incoming.setData(null);
            finish();
        }
    }
}
