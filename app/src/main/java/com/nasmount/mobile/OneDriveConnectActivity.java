package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import net.openid.appauth.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** No WebView credential interception. The system browser owns Microsoft sign-in. */
public final class OneDriveConnectActivity extends LocalizedActivity {
    static final int REQUEST=7392;
    private static final int AUTHORIZE=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AuthorizationService service;
    private String id,name;private boolean readOnly,waiting,exchanging;
    static Intent intent(Activity a,String id,String name,boolean readOnly){
        return new Intent(a,OneDriveConnectActivity.class).putExtra("id",id).putExtra("name",name).putExtra("readOnly",readOnly);
    }
    @Override public void onCreate(Bundle saved){
        Ui.applyAppearance(this);setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout l=Ui.vertical(this);l.setGravity(Gravity.CENTER);l.setBackgroundColor(Ui.BG);
        l.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));
        TextView title=Ui.text(this,"OneDrive",21,Ui.TEXT,true);title.setGravity(Gravity.CENTER);
        l.addView(title,Ui.lp(this,-1,52));l.addView(new ProgressBar(this),Ui.lp(this,40,40));
        TextView status=Ui.text(this,L10n.t("Microsoft 계정 연결 중..."),14,Ui.MUTED,false);status.setGravity(Gravity.CENTER);
        l.addView(status,Ui.lp(this,-1,48));setContentView(l);
        id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("name");readOnly=getIntent().getBooleanExtra("readOnly",false);
        if(name==null||name.length()>512||name.indexOf(0)>=0){fail("OneDrive 연결에 실패했습니다.");return;}
        if(!OneDriveAuth.configured()){fail("OneDrive 앱 인증 등록이 필요합니다.");return;}
        service=new AuthorizationService(this);
        waiting=saved!=null&&saved.getBoolean("waiting");
        if(saved!=null&&saved.getBoolean("exchanging")){fail("OneDrive 연결에 실패했습니다.");return;}
        if(waiting)return;
        if(id!=null)worker.execute(()->{
            try{Models.RemoteProfile p=find(new SecureStore(this),id);
                if(p==null||!OneDriveAuth.linked(p))throw new IllegalStateException();
                runOnUiThread(()->launch(p.username));
            }catch(Exception e){runOnUiThread(()->fail("OneDrive 연결에 실패했습니다."));}
        });
        else launch("");
    }
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("waiting",waiting);b.putBoolean("exchanging",exchanging);super.onSaveInstanceState(b);}
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private void launch(String hint){
        if(!alive())return;
        try{waiting=true;startActivityForResult(service.getAuthorizationRequestIntent(OneDriveAuth.request(this,hint)),AUTHORIZE);}
        catch(Exception e){waiting=false;fail("OneDrive 연결에 실패했습니다.");}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);if(req!=AUTHORIZE)return;
        waiting=false;
        if(result!=RESULT_OK||data==null){finish();return;}
        AuthorizationResponse response=AuthorizationResponse.fromIntent(data);
        AuthorizationException error=AuthorizationException.fromIntent(data);
        if(response==null||error!=null||response.authorizationCode==null||
           !BuildConfig.ONEDRIVE_CLIENT_ID.equals(response.request.clientId)||
           !OneDriveAuth.redirect(this).equals(response.request.redirectUri)||
           !OneDriveAuth.config().authorizationEndpoint.equals(response.request.configuration.authorizationEndpoint)||
           !OneDriveAuth.config().tokenEndpoint.equals(response.request.configuration.tokenEndpoint)||
           response.request.codeVerifier==null){fail("OneDrive 연결에 실패했습니다.");return;}
        exchanging=true;
        AuthState state=new AuthState(response,null);
        service.performTokenRequest(response.createTokenExchangeRequest(),(tokens,exception)->{
            if(!alive())return;
            if(tokens==null||exception!=null||tokens.accessToken==null||tokens.refreshToken==null){
                fail("OneDrive 연결에 실패했습니다.");return;
            }
            state.update(tokens,null);
            worker.execute(()->finishLink(state,tokens.accessToken));
        });
    }
    private static Models.RemoteProfile find(SecureStore store,String id)throws Exception{
        for(Models.RemoteProfile p:store.supportedRemotes())if(id.equals(p.id))return p;return null;
    }
    private void finishLink(AuthState state,String token){
        try{
            OneDriveApi.Identity user=OneDriveApi.identity(token);
            synchronized(SecureStore.class){
                if(!alive())return;
                SecureStore store=new SecureStore(this);
                Models.RemoteProfile p=id==null?new Models.RemoteProfile():find(store,id);
                String marker=OneDriveAuth.marker(user.userId,user.driveId);
                if(p==null||id!=null&&(!OneDriveAuth.linked(p)||!p.fingerprint.equals(marker)))throw new SecurityException();
                if(!store.canSaveRemote(id,OneDriveAuth.PROTOCOL))throw new RemoteEditorPolicy.Invalid("limit");
                p.protocol=OneDriveAuth.PROTOCOL;p.host=OneDriveAuth.HOST;p.port=0;p.root="/";
                p.username=user.email;p.password="";p.fingerprint=marker;
                p.name=name.trim().isEmpty()?"OneDrive":name.trim();p.readOnly=readOnly;p.ssl=true;
                OneDriveAuth.remember(p,state);
                try{store.putRemote(p);}catch(Exception e){if(id==null)OneDriveAuth.forget(p);throw e;}
            }
            runOnUiThread(()->{if(alive()){setResult(RESULT_OK);finish();}});
        }catch(RemoteEditorPolicy.Invalid e){runOnUiThread(()->fail("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));}
        catch(Exception e){runOnUiThread(()->fail("OneDrive 연결에 실패했습니다."));}
    }
    private void fail(String message){
        if(alive())new AlertDialog.Builder(this).setTitle("OneDrive").setMessage(L10n.t(message))
            .setPositiveButton(L10n.t("확인"),(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
    @Override protected void onDestroy(){worker.shutdownNow();if(service!=null)service.dispose();super.onDestroy();}
}
