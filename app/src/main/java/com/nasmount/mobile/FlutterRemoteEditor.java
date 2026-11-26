package com.nasmount.mobile;

import android.app.Activity;
import android.content.Intent;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Activity-scoped editor session. Existing and web-acquired secrets stay native. */
final class FlutterRemoteEditor {
    static final String RETURN_FLUTTER="filehub.editor.return_flutter";
    private final Activity activity;
    private final ExecutorService worker;
    private final String id;
    private final java.util.function.Consumer<String> completion;
    private volatile boolean closed;
    private Models.RemoteProfile baseline;
    private boolean initialized, busy;
    private String session;
    private MethodChannel.Result googleResult;
    private boolean microsoftPending;
    FlutterRemoteEditor(Activity activity,ExecutorService worker) {
        this(activity,worker,activity.getIntent().getStringExtra(FlutterUiContract.PROFILE_ID),null);
    }
    FlutterRemoteEditor(Activity activity,ExecutorService worker,String id,
                        java.util.function.Consumer<String> completion) {
        this.activity=activity;this.worker=worker;this.id=id;this.completion=completion;
    }
    private Models.RemoteProfile find(SecureStore store) throws Exception {
        if(id==null) return null;
        for(Models.RemoteProfile p:store.supportedRemotes())
            if(id.equals(p.id)) return p;
        throw new RemoteEditorPolicy.Invalid("stale");
    }
    private boolean alive() { return !closed&&!activity.isDestroyed()&&!activity.isFinishing(); }
    private void finish(String action) {
        session=null;
        if(completion!=null){completion.accept(action);return;}
        activity.setResult(Activity.RESULT_OK,new Intent()
                .putExtra(FlutterUiContract.ACTION,action)
                .putExtra(FlutterUiContract.PROFILE_ID,id)
                .putExtra(RETURN_FLUTTER,activity.getIntent().getBooleanExtra(RETURN_FLUTTER,false)));
        session=null;
        activity.finish();
    }
    void snapshot(Map<String,Object> data,MethodChannel.Result result) {
        if(closed||busy) { result.error("busy",null,null); return; }
        worker.execute(()->{
            try {
                SecureStore store=new SecureStore(activity);
                Models.RemoteProfile p=find(store);
                if(p!=null && !store.remoteAllowed(p.id))
                    throw new RemoteEditorPolicy.Invalid("limit");
                if(!initialized) { baseline=p; initialized=true; }
                // Never silently replace a draft with a newer on-disk profile.
                Map<String,Object> fields=RemoteEditorPolicy.fields(baseline);
                fields.put("ftpOptions",Arrays.asList(RemoteEditorPolicy.FTP_CHARSETS));
                fields.put("sftpOptions",Arrays.asList(SftpFilenameCharset.OPTIONS));
                fields.put("ftpLabels",Arrays.asList(L10n.labels(RemoteEditorPolicy.FTP_CHARSETS)));
                fields.put("sftpLabels",Arrays.asList(L10n.labels(SftpFilenameCharset.OPTIONS)));
                data.put("editor",fields);
                activity.runOnUiThread(()->{if(alive())result.success(data);});
            } catch(Exception error) {
                activity.runOnUiThread(()->{if(alive())result.error("stale",null,null);});
            }
        });
    }
    boolean handle(MethodCall call,MethodChannel.Result result) {
        if(closed){result.error("stale",null,null);return true;}
        switch(call.method) {
            case "editorSave":
                if(busy||!initialized||!(call.arguments instanceof Map)) {
                    result.error("invalid",null,null);return true;
                }
                busy=true;
                final Map<?,?> draft=new HashMap<>((Map<?,?>)call.arguments);
                if(GoogleDriveAuth.PROTOCOL.equals(draft.get("protocol"))||OneDriveAuth.PROTOCOL.equals(draft.get("protocol"))){
                    boolean microsoft=OneDriveAuth.PROTOCOL.equals(draft.get("protocol"));
                    if((baseline!=null&&!(microsoft?OneDriveAuth.linked(baseline):GoogleDriveAuth.linked(baseline)))||
                        !(draft.get("name") instanceof String)||((String)draft.get("name")).length()>512||
                        !(draft.get("readOnly") instanceof Boolean)){
                        busy=false;result.error("invalid",null,null);return true;
                    }
                    try{
                        googleResult=result;microsoftPending=microsoft;
                        if(microsoft)activity.startActivityForResult(OneDriveConnectActivity.intent(activity,id,
                            (String)draft.get("name"),(Boolean)draft.get("readOnly")),OneDriveConnectActivity.REQUEST);
                        else activity.startActivityForResult(GoogleDriveConnectActivity.intent(activity,id,
                            (String)draft.get("name"),(Boolean)draft.get("readOnly")),GoogleDriveConnectActivity.REQUEST);
                    }catch(RuntimeException e){googleResult=null;busy=false;result.error(microsoft?"oneDriveFailed":"googleFailed",null,null);}
                    finally{draft.clear();}
                    return true;
                }
                if(baseline!=null&&(GoogleDriveAuth.PROTOCOL.equals(baseline.protocol)||OneDriveAuth.PROTOCOL.equals(baseline.protocol))){
                    busy=false;draft.clear();result.error("invalid",null,null);return true;
                }
                final String capturedSession=session;
                worker.execute(()->{
                    String error=null;
                    try {
                        synchronized(SecureStore.class) {
                            SecureStore store=new SecureStore(activity);
                            Models.RemoteProfile live=find(store);
                            if(id!=null && !RemoteEditorPolicy.sameEditableRevision(baseline,live))
                                throw new RemoteEditorPolicy.Invalid("stale");
                            if(id!=null && !store.remoteAllowed(id))
                                throw new RemoteEditorPolicy.Invalid("limit");
                            Models.RemoteProfile updated=RemoteEditorPolicy.apply(live,draft,capturedSession);
                            if(!store.canSaveRemote(id,updated.protocol))
                                throw new RemoteEditorPolicy.Invalid("limit");
                            store.putRemote(updated);
                        }
                    } catch(RemoteEditorPolicy.Invalid e) { error=e.code; }
                    catch(Exception e) { error="saveFailed"; }
                    finally { draft.clear(); }
                    final String failure=error;
                    activity.runOnUiThread(()->{
                        busy=false;
                        if(!alive())return;
                        if(failure==null) { result.success(null); finish("saved"); }
                        else {
                            result.error(failure,null,null);
                            if("limit".equals(failure))EditionMonetization.showUpgrade(activity);
                        }
                    });
                });
                return true;
            case "editorLogin":
                if(busy||!initialized) {result.error("busy",null,null);return true;}
                busy=true;
                final boolean[] acquired={false};
                try {
                    new RemoteCloudUi(activity).login(value->{session=value;acquired[0]=true;},()->{
                        busy=false;
                        if(alive())result.success(acquired[0]);
                    });
                } catch(RuntimeException error) {busy=false;result.error("session",null,null);}
                return true;
            case "editorHelp":
                if(busy) {result.error("busy",null,null);return true;}
                if("TeraBox".equals(call.arguments))new RemoteCloudUi(activity).showTeraBoxPcHelp();
                else if("MYBOX".equals(call.arguments))new RemoteCloudUi(activity).showMyBoxPatHelp();
                else {result.error("invalid",null,null);return true;}
                result.success(null);return true;
            case "editorClassic":
                if(busy) {result.error("busy",null,null);return true;}
                result.success(null);finish("classic");return true;
            default:return false;
        }
    }
    boolean googleActivityResult(int request,int code){
        if(request!=(microsoftPending?OneDriveConnectActivity.REQUEST:GoogleDriveConnectActivity.REQUEST)||googleResult==null)return false;
        MethodChannel.Result result=googleResult;googleResult=null;busy=false;
        if(alive()){
            if(code==Activity.RESULT_OK){
                if(microsoftPending&&completion!=null){finish("saved");result.success(null);}
                else{result.success(null);finish("saved");}
            }
            else result.error(microsoftPending?"oneDriveCanceled":"googleCanceled",null,null);
        }
        return true;
    }
    boolean isBusy(){return busy;}
    void close(){closed=true;session=null;baseline=null;googleResult=null;}
    static void labels(Map<String,String> m) {
        m.put("oneDriveConnect",L10n.t("Microsoft 계정 연결"));
        m.put("oneDriveHint",L10n.t("Microsoft에 로그인하고 OneDrive 접근을 허용하세요."));
        m.put("oneDriveReconnect",L10n.t("기존 Microsoft 계정으로 다시 로그인합니다."));
        m.put("err_oneDriveCanceled",L10n.t("Microsoft 계정 연결이 취소되었습니다."));
        m.put("err_oneDriveFailed",L10n.t("OneDrive 연결에 실패했습니다."));
        m.put("googleConnect",L10n.t("Google 계정 연결"));
        m.put("googleHint",L10n.t("휴대폰의 Google 계정을 선택하고 접근 권한을 허용하세요."));
        m.put("googleReconnect",L10n.t("기존 Google 계정으로 다시 로그인합니다."));
        m.put("err_googleCanceled",L10n.t("Google 계정 연결이 취소되었습니다."));
        m.put("err_googleFailed",L10n.t("Google Drive 연결에 실패했습니다. 앱 인증 및 네트워크를 확인하세요."));
        m.put("editorAdd",L10n.t("원격 저장소 추가"));
        m.put("networkHeading",L10n.t("네트워크 폴더"));
        m.put("cloudHeading",L10n.t("클라우드 저장소"));
        m.put("advanced",L10n.t("고급 설정"));
        m.put("secureConnection",L10n.t("연결 보안"));
        m.put("editorEdit",L10n.t("원격 저장소 수정"));
        m.put("protocol",L10n.t("연결 방식"));
        m.put("name",L10n.t("표시할 이름"));
        m.put("host",L10n.t("서버 주소"));
        m.put("hostHint",L10n.t("호스트 또는 IP"));
        m.put("port",L10n.t("포트"));
        m.put("root",L10n.t("공유/시작 경로"));
        m.put("rootSmb",L10n.t("공유이름/하위경로 (필수)"));
        m.put("rootSftp",L10n.t("비우면 로그인 홈 폴더 · / 입력하면 루트부터"));
        m.put("rootOther",L10n.t("선택 사항 · 시작 경로 /"));
        m.put("username",L10n.t("사용자명"));
        m.put("anonymous",L10n.t("익명 로그인"));
        m.put("password",L10n.t("비밀번호"));
        m.put("ndus",L10n.t("TeraBox 로그인 세션 (ndus)"));
        m.put("pat",L10n.t("네이버 MYBOX 액세스 토큰 (PAT)"));
        m.put("ssl",L10n.t("WebDAV HTTPS 보안 연결 사용"));
        m.put("ftpTls",L10n.t("FTP 연결 보안"));
        m.put("ftpPlain",L10n.t("일반 FTP"));
        m.put("ftpCharset",L10n.t("FTP 파일명 문자셋"));
        m.put("sftpCharset",L10n.t("SFTP 파일명 문자셋"));
        m.put("ftpPasv",L10n.t("FTP 수동 모드(PASV)"));
        m.put("readOnly",L10n.t("읽기 전용 (변경·삭제·업로드 차단)"));
        m.put("save",L10n.t("저장"));
        m.put("cancel",L10n.t("취소"));
        m.put("classic",L10n.t("기존 연결 설정 화면"));
        m.put("keep",L10n.t("기존 로그인 정보 유지"));
        m.put("replace",L10n.t("로그인 정보 변경"));
        m.put("emptySecret",L10n.t("비밀번호 없이 연결"));
        m.put("sessionReady",L10n.t("웹 로그인 세션을 가져왔습니다."));
        m.put("login",L10n.t("TeraBox 계정으로 웹 로그인 · 세션 가져오기"));
        m.put("pcHelp",L10n.t("PC에서 가져오는 방법"));
        m.put("patHelp",L10n.t("PAT 발급 방법"));
        m.put("cloudHint",L10n.t("TeraBox 자체 계정으로 로그인한 후 세션 가져오기를 누르세요. "));
        m.put("patHint",L10n.t("네이버 MYBOX 공식 Open API PAT가 필요합니다. "));
        m.put("err_invalid",L10n.t("설정을 확인해 주세요"));
        m.put("err_host",L10n.t("서버 주소에는 호스트 또는 IP만 입력하세요."));
        m.put("err_port",L10n.t("포트 범위는 1~65535입니다."));
        m.put("err_share",L10n.t("SMB 공유 이름을 입력하세요."));
        m.put("err_path",L10n.t("허용되지 않는 경로입니다."));
        m.put("err_user",L10n.t("사용자명을 입력하세요."));
        m.put("err_charset",L10n.t("지원하지 않는 파일명 문자셋입니다."));
        m.put("err_replaceRequired",L10n.t("서버 또는 계정 정보가 변경되었습니다. 로그인 정보를 다시 입력하세요."));
        m.put("err_session",L10n.t("TeraBox ndus 쿠키 형식을 확인하세요."));
        m.put("err_pat",L10n.t("MYBOX PAT는 mbx_pat_로 시작해야 합니다."));
        m.put("err_stale",L10n.t("연결 설정이 변경되었습니다. 화면을 닫고 다시 열어 주세요."));
        m.put("err_limit",L10n.t("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));
        m.put("err_saveFailed",L10n.t("연결 설정을 저장하지 못했습니다."));
    }
}
