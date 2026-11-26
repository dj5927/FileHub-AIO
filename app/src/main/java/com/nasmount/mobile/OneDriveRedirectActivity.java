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
                OneDriveCallbackPolicy.parse(raw,OneDriveAuth.redirectForPackage(getPackageName()));
                OneDriveDiagnostics.mark(this,"OD34_REDIRECT_RECEIVED",null);
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
