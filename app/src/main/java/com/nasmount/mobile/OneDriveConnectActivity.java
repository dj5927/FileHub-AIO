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

/** Browser OAuth, explicit verification and persistence before RESULT_OK. */
public final class OneDriveConnectActivity extends LocalizedActivity {
    static final int REQUEST=7392;
    private static final int AUTHORIZE=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AuthorizationService service;
    private String id,name;
    private boolean readOnly,waiting,exchanging;
    private volatile boolean terminal;
    private TextView status;
    private AlertDialog errorDialog;
    static Intent intent(Activity a,String id,String name,boolean readOnly){
        return new Intent(a,OneDriveConnectActivity.class).putExtra("id",id).putExtra("name",name).putExtra("readOnly",readOnly);
    }
    @Override public void onCreate(Bundle saved){
        Ui.applyAppearance(this);setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setResult(RESULT_CANCELED);
        LinearLayout l=Ui.vertical(this);l.setGravity(Gravity.CENTER);l.setBackgroundColor(Ui.BG);
        l.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));
        TextView title=Ui.text(this,"OneDrive",21,Ui.TEXT,true);title.setGravity(Gravity.CENTER);
        l.addView(title,Ui.lp(this,-1,52));l.addView(new ProgressBar(this),Ui.lp(this,40,40));
        status=Ui.text(this,L10n.t("Microsoft 계정 연결 중..."),14,Ui.MUTED,false);status.setGravity(Gravity.CENTER);
        l.addView(status,Ui.lp(this,-1,48));setContentView(l);
        if(saved==null)OneDriveDiagnostics.start(this);
        id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("name");readOnly=getIntent().getBooleanExtra("readOnly",false);
        if(name==null||name.length()>512||name.indexOf(0)>=0){fail("OD02_INVALID_REQUEST",null);return;}
        if(!OneDriveAuth.configured()){fail("OD03_NOT_CONFIGURED",null);return;}
        waiting=saved!=null&&saved.getBoolean("waiting");
        if(saved!=null&&saved.getBoolean("exchanging")){fail("OD04_INTERRUPTED",null);return;}
        try{service=new AuthorizationService(this);}
        catch(RuntimeException|LinkageError e){fail("OD05_BROWSER_INIT",e);return;}
        if(waiting)return;
        if(id!=null)submit(()->{
            try{Models.RemoteProfile p=find(new SecureStore(this),id);
                if(p==null||!OneDriveAuth.linked(p))throw new IllegalStateException();
                runOnUiThread(()->launch(p.username.contains("@")?p.username:""));
            }catch(Exception e){runOnUiThread(()->fail("OD06_PROFILE",e));}
        });
        else launch("");
    }
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("waiting",waiting);b.putBoolean("exchanging",exchanging);super.onSaveInstanceState(b);}
    private boolean alive(){return !terminal&&!isFinishing()&&!isDestroyed();}
    private void launch(String hint){
        if(!alive()||waiting||exchanging)return;
        try{
            waiting=true;OneDriveDiagnostics.mark(this,"OD07_BROWSER",null);
            startActivityForResult(service.getAuthorizationRequestIntent(OneDriveAuth.request(this,hint)),AUTHORIZE);
        }catch(RuntimeException|LinkageError e){waiting=false;fail("OD08_BROWSER_LAUNCH",e);}
    }
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req!=AUTHORIZE||!alive()||exchanging)return;
        waiting=false;
        if(data==null||result!=RESULT_OK){
            OneDriveDiagnostics.mark(this,"OD09_CANCELED",null);
            terminal=true;finish();return;
        }
        try{
            AuthorizationResponse response=AuthorizationResponse.fromIntent(data);
            AuthorizationException error=AuthorizationException.fromIntent(data);
            if(error!=null){fail("OD10_AUTHORIZATION",error);return;}
            if(response==null||response.authorizationCode==null||
               !BuildConfig.ONEDRIVE_CLIENT_ID.equals(response.request.clientId)||
               !OneDriveAuth.redirect(this).equals(response.request.redirectUri)||
               !OneDriveAuth.config().authorizationEndpoint.equals(response.request.configuration.authorizationEndpoint)||
               !OneDriveAuth.config().tokenEndpoint.equals(response.request.configuration.tokenEndpoint)||
               response.request.codeVerifier==null){fail("OD11_CALLBACK",null);return;}
            exchanging=true;
            OneDriveDiagnostics.mark(this,"OD12_CALLBACK_OK",null);
            status.setText(L10n.t("OneDrive 인증을 확인하는 중..."));
            AuthState state=new AuthState(response,null);
            service.performTokenRequest(response.createTokenExchangeRequest(),(tokens,exception)->{
                runOnUiThread(()->{
                    if(!alive())return;
                    try{
                        if(tokens==null||exception!=null||tokens.accessToken==null||tokens.accessToken.isEmpty()){
                            fail("OD13_TOKEN",exception);return;
                        }
                        if(tokens.refreshToken==null||tokens.refreshToken.isEmpty()){
                            fail("OD14_REFRESH_TOKEN",null);return;
                        }
                        state.update(tokens,null);
                        OneDriveDiagnostics.mark(this,"OD15_TOKEN_OK",null);
                        status.setText(L10n.t("OneDrive 저장소를 확인하는 중..."));
                        submit(()->finishLink(state,tokens.accessToken));
                    }catch(RuntimeException|LinkageError e){fail("OD16_TOKEN_CALLBACK",e);}
                });
            });
        }catch(RuntimeException|LinkageError e){fail("OD17_EXCHANGE",e);}
    }
    private void submit(Runnable task){
        if(!alive())return;
        try{worker.execute(task);}catch(RuntimeException e){fail("OD18_WORKER",e);}
    }
    private static Models.RemoteProfile find(SecureStore store,String id)throws Exception{
        for(Models.RemoteProfile p:store.supportedRemotes())if(id.equals(p.id))return p;return null;
    }
    private void finishLink(AuthState state,String token){
        String stage="OD19_GRAPH_ACCOUNT";
        try{
            OneDriveApi.Identity user=OneDriveApi.identity(token);
            OneDriveDiagnostics.mark(this,"OD20_GRAPH_OK",null);
            stage="OD21_PROFILE";
            synchronized(SecureStore.class){
                if(!alive())return;
                SecureStore store=new SecureStore(this);
                String marker=OneDriveAuth.marker(user.userId,user.driveId);
                Models.RemoteProfile p=OneDriveLinkPolicy.choose(store.supportedRemotes(),id,marker);
                if(!store.canSaveRemote(p.id,OneDriveAuth.PROTOCOL))throw new RemoteEditorPolicy.Invalid("limit");
                p.protocol=OneDriveAuth.PROTOCOL;p.host=OneDriveAuth.HOST;p.port=0;p.root="/";
                p.username=user.email;p.password="";p.fingerprint=marker;
                if(id!=null||!name.trim().isEmpty()||p.name.isEmpty())p.name=name.trim().isEmpty()?"OneDrive":name.trim();
                p.readOnly=id==null&&p.readOnly||readOnly;p.ssl=true;
                stage="OD22_SAVE";
                OneDriveAuth.commitLink(store,p,state);
                if(!OneDriveLinkPolicy.visible(store.supportedRemotes(),p.id,marker))
                    throw new java.io.IOException("OneDrive profile not visible after commit");
                OneDriveDiagnostics.mark(this,"OD23_SAVED",null);
            }
            runOnUiThread(()->{
                if(!alive())return;
                terminal=true;OneDriveDiagnostics.mark(this,"OD24_RESULT_OK",null);
                setResult(RESULT_OK);finish();
            });
        }catch(RemoteEditorPolicy.Invalid e){runOnUiThread(()->fail("OD25_LIMIT",e));}
        catch(Exception|LinkageError e){
            final String failedStage=stage;
            runOnUiThread(()->fail(failedStage,e));
        }
    }
    private void fail(String stage,Throwable error){
        if(!alive())return;
        terminal=true;
        OneDriveDiagnostics.mark(this,stage,error);
        String message=L10n.t(stage.equals("OD03_NOT_CONFIGURED")?"OneDrive 앱 인증 등록이 필요합니다.":
            stage.equals("OD25_LIMIT")?"무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다.":"OneDrive 연결에 실패했습니다.");
        try{
            errorDialog=new AlertDialog.Builder(this).setTitle("OneDrive")
                .setMessage(message+"\n\n"+OneDriveDiagnostics.code(stage,error))
                .setPositiveButton(L10n.t("확인"),(d,w)->finish()).setOnCancelListener(d->finish()).show();
        }catch(RuntimeException e){finish();}
    }
    @Override public void onBackPressed(){
        terminal=true;OneDriveDiagnostics.mark(this,"OD26_BACK",null);
        setResult(RESULT_CANCELED);finish();
    }
    @Override protected void onDestroy(){
        terminal=true;worker.shutdownNow();
        if(errorDialog!=null)try{errorDialog.dismiss();}catch(RuntimeException ignored){}
        if(service!=null)try{service.dispose();}catch(RuntimeException ignored){}
        super.onDestroy();
    }
}
