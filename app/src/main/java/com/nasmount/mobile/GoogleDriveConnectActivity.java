package com.nasmount.mobile;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.app.AlertDialog;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.ApiException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private Activity owns Google account chooser and consent. No passwords or client secrets. */
public final class GoogleDriveConnectActivity extends LocalizedActivity {
    static final int REQUEST=7391;
    private static final int ACCOUNT=1,CONSENT=2;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private String account="",id,name;
    private boolean readOnly,renewalRequested,revoking,linking;
    private int waiting;
    static Intent intent(Activity a,String id,String name,boolean readOnly){
        return new Intent(a,GoogleDriveConnectActivity.class).putExtra("id",id)
            .putExtra("name",name).putExtra("readOnly",readOnly);
    }
    @Override public void onCreate(Bundle saved){
        Ui.applyAppearance(this);setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout=Ui.vertical(this);layout.setGravity(Gravity.CENTER);layout.setBackgroundColor(Ui.BG);
        layout.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));
        TextView title=Ui.text(this,"Google Drive",21,Ui.TEXT,true);title.setGravity(Gravity.CENTER);
        layout.addView(title,Ui.lp(this,-1,52));layout.addView(new ProgressBar(this),Ui.lp(this,40,40));
        TextView status=Ui.text(this,L10n.t("Google 계정 연결 중..."),14,Ui.MUTED,false);status.setGravity(Gravity.CENTER);
        layout.addView(status,Ui.lp(this,-1,48));setContentView(layout);
        id=getIntent().getStringExtra("id");name=getIntent().getStringExtra("name");
        readOnly=getIntent().getBooleanExtra("readOnly",false);
        if(name==null||name.length()>512||name.indexOf(0)>=0){fail("연결 설정을 저장하지 못했습니다.");return;}
        if(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)!=ConnectionResult.SUCCESS){
            fail("Google Play 서비스가 필요합니다.");return;
        }
        if(saved!=null){
            account=saved.getString("account","");waiting=saved.getInt("waiting");
            renewalRequested=saved.getBoolean("renewalRequested");
            if(saved.getBoolean("revoking")||saved.getBoolean("linking")){
                fail("Google 계정 연결이 중단되었습니다. 다시 시도하세요.");return;
            }
        }
        if(waiting!=0)return;
        if(!account.isEmpty()){authorize();return;}
        if(id!=null){
            worker.execute(()->{
                try{
                    Models.RemoteProfile p=find(new SecureStore(this),id);
                    if(p==null||!GoogleDriveAuth.linked(p))throw new IllegalStateException();
                    runOnUiThread(()->{if(alive()){account=p.username;authorize();}});
                }catch(Exception e){runOnUiThread(()->fail("연결 설정이 변경되었습니다. 화면을 닫고 다시 열어 주세요."));}
            });
        }else choose();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("account",account);state.putInt("waiting",waiting);
        state.putBoolean("renewalRequested",renewalRequested);
        state.putBoolean("revoking",revoking);state.putBoolean("linking",linking);
        super.onSaveInstanceState(state);
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private void choose(){
        try{
            waiting=ACCOUNT;
            startActivityForResult(AccountManager.newChooseAccountIntent(null,null,new String[]{"com.google"},null,null,null,null),ACCOUNT);
        }catch(Exception e){waiting=0;fail("Google Play 서비스가 필요합니다.");}
    }
    private void authorize(){
        if(!alive())return;
        try{
            Identity.getAuthorizationClient(this).authorize(GoogleDriveAuth.request(account))
                .addOnSuccessListener(this,this::authorized)
                .addOnFailureListener(this,e->failAuth(e));
        }catch(Exception e){failAuth(e);}
    }
    private void authorized(AuthorizationResult result){
        if(!alive())return;
        try{
            switch(GoogleDriveConsentPolicy.action(result.hasResolution(),renewalRequested)){
                case LAUNCH_GOOGLE_CONSENT:
                    waiting=CONSENT;
                    startIntentSenderForResult(result.getPendingIntent().getIntentSender(),CONSENT,null,0,0,0);
                    return;
                case RENEWAL_NOT_DISPLAYED:
                    // Do not silently accept a cached token after the user explicitly asked for Google consent.
                    fail("Google 승인 화면이 표시되지 않았습니다. Google 계정의 연결 관리에서 FileHub AIO 권한을 해제한 뒤 다시 시도하세요.");
                    return;
                default:
                    offerExistingOrRenew(GoogleDriveAuth.granted(result));
            }
        }catch(Exception e){waiting=0;failAuth(e);}
    }
    private void offerExistingOrRenew(String token){
        if(!alive())return;
        new AlertDialog.Builder(this)
            .setTitle(L10n.t("Google Drive 접근 권한"))
            .setMessage(L10n.t("이미 승인된 Google 계정입니다. 기존 권한으로 연결하거나 Google의 공식 승인 화면에서 다시 승인할 수 있습니다. 다시 승인하면 기존 권한이 해제되므로 같은 Google Cloud 프로젝트의 PC판 등에서 재연결이 필요할 수 있습니다."))
            .setPositiveButton(L10n.t("Google 권한 다시 승인"),(d,w)->renewConsent())
            .setNegativeButton(L10n.t("기존 권한으로 연결"),(d,w)->finishLink(token))
            .setNeutralButton(L10n.t("취소"),(d,w)->finish())
            .setOnCancelListener(d->finish()).show();
    }
    private void renewConsent(){
        if(!alive()||revoking||linking)return;
        revoking=true;
        worker.execute(()->{
            try{
                // Re-check edition entitlement before revoking a real account grant.
                SecureStore store=new SecureStore(this);
                if(!store.canSaveRemote(id,GoogleDriveAuth.PROTOCOL))
                    throw new RemoteEditorPolicy.Invalid("limit");
                GoogleDriveAuth.revokeDriveGrant(account);
                runOnUiThread(()->{
                    if(!alive())return;
                    revoking=false;renewalRequested=true;authorize();
                });
            }catch(RemoteEditorPolicy.Invalid e){
                runOnUiThread(()->{revoking=false;fail("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다.");});
            }catch(Exception e){
                runOnUiThread(()->{revoking=false;fail("Google 권한 해제에 실패했습니다. Google 계정의 연결 관리에서 권한을 확인하세요.");});
            }
        });
    }
    @Override protected void onActivityResult(int req,int code,Intent data){
        super.onActivityResult(req,code,data);
        if(req!=ACCOUNT&&req!=CONSENT)return;
        waiting=0;
        if(code!=RESULT_OK||data==null){setResult(RESULT_CANCELED);finish();return;}
        if(req==ACCOUNT){
            account=data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if(account==null||account.isEmpty()||!"com.google".equals(data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE))){
                fail("Google 계정을 확인할 수 없습니다.");return;
            }
            authorize();
        }else try{
            finishLink(GoogleDriveAuth.granted(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)));
        }catch(Exception e){failAuth(e);}
    }
    private static Models.RemoteProfile find(SecureStore store,String id)throws Exception{
        for(Models.RemoteProfile p:store.supportedRemotes())if(id.equals(p.id))return p;
        return null;
    }
    private void finishLink(String token){
        if(!alive()||linking)return;
        linking=true;
        worker.execute(()->{
            try{
                GoogleDriveApi.Identity user=GoogleDriveApi.identity(token);
                if(!account.equalsIgnoreCase(user.email))throw new SecurityException("account");
                if(!alive())return;
                Models.RemoteProfile p;
                synchronized(SecureStore.class){
                    SecureStore store=new SecureStore(this);
                    p=id==null?new Models.RemoteProfile():find(store,id);
                    if(p==null||id!=null&&(!GoogleDriveAuth.linked(p)||
                        !p.username.equalsIgnoreCase(user.email)||
                        !p.fingerprint.equals(GoogleDriveAuth.marker(user.permissionId))))throw new SecurityException("account");
                    if(!store.canSaveRemote(id,GoogleDriveAuth.PROTOCOL))throw new RemoteEditorPolicy.Invalid("limit");
                    p.protocol=GoogleDriveAuth.PROTOCOL;p.host=GoogleDriveAuth.HOST;p.port=0;p.root="/";
                    p.username=user.email;p.password="";p.fingerprint=GoogleDriveAuth.marker(user.permissionId);
                    p.name=name.trim().isEmpty()?"Google Drive":name.trim();p.readOnly=readOnly;p.ssl=true;
                    if(!alive())return;
                    store.putRemote(p);GoogleDriveAuth.remember(p,token);
                }
                runOnUiThread(()->{if(alive()){setResult(RESULT_OK);finish();}});
            }catch(RemoteEditorPolicy.Invalid e){
                runOnUiThread(()->{if(alive())fail("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다.");});
            }catch(SecurityException e){runOnUiThread(()->fail("Google 계정을 확인할 수 없습니다."));}
            catch(Exception e){runOnUiThread(()->fail("Google Drive 연결에 실패했습니다. 앱 인증 및 네트워크를 확인하세요."));}
        });
    }
    private void failAuth(Exception e){
        int code=e instanceof ApiException?((ApiException)e).getStatusCode():-1;
        // Never display SDK raw messages; they may contain user account or token details.
        fail("Google Drive 연결에 실패했습니다. 앱 인증 및 네트워크를 확인하세요.",code);
    }
    private void fail(String message){fail(message,-1);}
    private void fail(String message,int code){
        if(!alive())return;
        new AlertDialog.Builder(this).setTitle("Google Drive").setMessage(L10n.t(message)+(code>=0?" ("+code+")":""))
            .setPositiveButton(L10n.t("확인"),(d,w)->finish()).setOnCancelListener(d->finish()).show();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
