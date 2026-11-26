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
    static final String CALLBACK="com.nasmount.mobile.ONEDRIVE_CALLBACK";
    static final String REFRESH="filehub.onedrive.refresh";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String id,name;
    private boolean readOnly,waiting,exchanging;
    private String pendingState;
    private volatile boolean terminal;
    private TextView status;
    private Button retry,cancel;
    private ProgressBar progress;
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable callbackMissing=()->{
        if(!alive()||!waiting||exchanging)return;
        OneDriveDiagnostics.mark(this,"OD37_RETURN_WITHOUT_CALLBACK",null);
        status.setText(L10n.t("OneDrive 인증 결과를 받지 못했습니다. 브라우저 인증을 다시 시도해 주세요."));
        progress.setVisibility(android.view.View.GONE);
        retry.setVisibility(android.view.View.VISIBLE);
    };
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
        l.addView(title,Ui.lp(this,-1,52));progress=new ProgressBar(this);l.addView(progress,Ui.lp(this,40,40));
        status=Ui.text(this,L10n.t("Microsoft 계정 연결 중..."),14,Ui.MUTED,false);status.setGravity(Gravity.CENTER);
        l.addView(status,Ui.lp(this,-1,-2));
        status.setPadding(0,Ui.dp(this,12),0,Ui.dp(this,12));
        retry=new Button(this);retry.setText(L10n.t("브라우저에서 다시 인증"));
        retry.setVisibility(android.view.View.GONE);retry.setOnClickListener(v->retryBrowser());
        l.addView(retry,Ui.lp(this,-1,-2));
        cancel=new Button(this);cancel.setText(L10n.t("취소"));cancel.setOnClickListener(v->onBackPressed());
        l.addView(cancel,Ui.lp(this,-1,-2));setContentView(l);
        // Browser return can create this activity in a new process.
        if(CALLBACK.equals(getIntent().getAction())){
            acceptCallback(getIntent());return;
        }
        if(saved==null)OneDriveDiagnostics.start(this);
        id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("name");readOnly=getIntent().getBooleanExtra("readOnly",false);
        if(name==null||name.length()>512||name.indexOf(0)>=0){fail("OD02_INVALID_REQUEST",null);return;}
        if(!OneDriveAuth.configured()){fail("OD03_NOT_CONFIGURED",null);return;}
        waiting=saved!=null&&saved.getBoolean("waiting");
        pendingState=saved==null?null:saved.getString("pendingState");
        if(saved!=null&&saved.getBoolean("exchanging")){fail("OD04_INTERRUPTED",null);return;}
        if(waiting){status.setText(L10n.t("브라우저에서 OneDrive 인증을 완료해 주세요."));return;}
        submit(()->prepare());
    }
    @Override protected void onSaveInstanceState(Bundle b){
        b.putBoolean("waiting",waiting);b.putBoolean("exchanging",exchanging);
        b.putString("pendingState",pendingState);super.onSaveInstanceState(b);
    }
    @Override protected void onResume(){
        super.onResume();
        ui.removeCallbacks(callbackMissing);
        if(waiting&&!exchanging&&!terminal)ui.postDelayed(callbackMissing,4000);
    }
    @Override protected void onPause(){
        ui.removeCallbacks(callbackMissing);
        super.onPause();
    }
    private void retryBrowser(){
        if(!alive()||!waiting||exchanging)return;
        ui.removeCallbacks(callbackMissing);
        waiting=false;retry.setVisibility(android.view.View.GONE);
        progress.setVisibility(android.view.View.VISIBLE);
        status.setText(L10n.t("Microsoft 계정 연결 중..."));
        OneDriveDiagnostics.mark(this,"OD38_RETRY",null);
        submit(()->prepare());
    }
    private boolean alive(){return !terminal&&!isFinishing()&&!isDestroyed();}
    private void prepare(){
        String stage="OD31_PREPARE";
        try{
            String hint="";
            if(id!=null){
                Models.RemoteProfile p=find(new SecureStore(this),id);
                if(p==null||!OneDriveAuth.linked(p))throw new IllegalStateException();
                if(p.username.contains("@"))hint=p.username;
            }
            AuthorizationRequest request=OneDriveAuth.request(this,hint);
            if(!alive())return;
            OneDrivePending.save(this,new OneDrivePending(request,id,name,readOnly,System.currentTimeMillis()));
            OneDriveDiagnostics.mark(this,"OD32_PENDING_SAVED",null);
            runOnUiThread(()->{
                if(!alive()){workerCleanup(request.state);return;}
                pendingState=request.state;
                try{
                    waiting=true;
                    status.setText(L10n.t("브라우저에서 OneDrive 인증을 완료해 주세요."));
                    OneDriveDiagnostics.mark(this,"OD07_BROWSER",null);
                    Intent browser=new Intent(Intent.ACTION_VIEW,request.toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // No synchronous Custom Tabs service/session calls.
                    startActivity(browser);
                    OneDriveDiagnostics.mark(this,"OD33_BROWSER_OPENED",null);
                    ui.removeCallbacks(callbackMissing);
                    ui.postDelayed(callbackMissing,4000);
                }catch(RuntimeException|LinkageError e){
                    workerCleanup(request.state);waiting=false;fail("OD08_BROWSER_LAUNCH",e);
                }
            });
        }catch(Exception|LinkageError e){runOnUiThread(()->fail(stage,e));}
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);
        if(intent!=null&&CALLBACK.equals(intent.getAction()))acceptCallback(intent);
    }
    private void acceptCallback(Intent intent){
        if(!alive()||exchanging)return;
        String raw=intent.getData()==null?null:intent.getData().toString();
        intent.setData(null);
        waiting=false;exchanging=true;
        ui.removeCallbacks(callbackMissing);
        retry.setVisibility(android.view.View.GONE);
        progress.setVisibility(android.view.View.VISIBLE);
        status.setText(L10n.t("OneDrive 인증을 확인하는 중..."));
        OneDriveDiagnostics.mark(this,"OD36_CALLBACK_DISPATCH",null);
        submit(()->{
            String stage="OD11_CALLBACK";
            try{
                OneDriveCallbackPolicy.Result callback=OneDriveCallbackPolicy.parse(raw,OneDriveAuth.redirectForPackage(getPackageName()));
                OneDrivePending pending=OneDrivePending.consume(this,callback);
                id=pending.id;name=pending.name;readOnly=pending.readOnly;
                OneDriveDiagnostics.mark(this,"OD12_CALLBACK_OK",null);
                if(callback.denied){runOnUiThread(()->fail("OD10_AUTHORIZATION",null));return;}
                AuthorizationResponse response=new AuthorizationResponse.Builder(pending.request)
                    .setState(callback.state).setAuthorizationCode(callback.code).build();
                stage="OD13_TOKEN";
                TokenResponse tokens=OneDriveAuth.exchange(response.createTokenExchangeRequest());
                if(tokens.refreshToken==null||tokens.refreshToken.isEmpty()){
                    runOnUiThread(()->fail("OD14_REFRESH_TOKEN",null));return;
                }
                AuthState state=new AuthState(response,null);state.update(tokens,null);
                OneDriveDiagnostics.mark(this,"OD15_TOKEN_OK",null);
                runOnUiThread(()->{if(alive())status.setText(L10n.t("OneDrive 저장소를 확인하는 중..."));});
                finishLink(state,tokens.accessToken);
            }catch(Exception|LinkageError e){
                final String failedStage=stage;
                runOnUiThread(()->fail(failedStage,e));
            }
        });
    }
    private void workerCleanup(String state){
        if(state!=null)try{worker.execute(()->OneDrivePending.cancel(this,state));}catch(RuntimeException ignored){}
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
                setResult(RESULT_OK);
                if(getCallingActivity()==null){
                    // Original caller may have been killed while the browser
                    // was open. Make the durable profile visible in the app.
                    try{
                        startActivity(new Intent(this,MainActivity.class).putExtra(REFRESH,true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    }catch(RuntimeException e){OneDriveDiagnostics.mark(this,"OD30_LIST_REFRESH_FAILED",e);}
                }
                finish();
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
        ui.removeCallbacks(callbackMissing);
        progress.setVisibility(android.view.View.GONE);
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
        String state=pendingState;
        if(state!=null)new Thread(()->OneDrivePending.cancel(getApplicationContext(),state),"onedrive-cancel").start();
        setResult(RESULT_CANCELED);finish();
    }
    @Override protected void onDestroy(){
        terminal=true;ui.removeCallbacksAndMessages(null);worker.shutdownNow();
        if(errorDialog!=null)try{errorDialog.dismiss();}catch(RuntimeException ignored){}
        super.onDestroy();
    }
}
