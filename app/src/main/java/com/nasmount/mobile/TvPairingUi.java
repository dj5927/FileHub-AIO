package com.nasmount.mobile;

import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.text.InputType;
import java.util.ArrayList;
import java.util.List;

/** TV QR prompt and opt-in phone -> TV encrypted profile transfer. */
final class TvPairingUi implements AutoCloseable {
    private final MainActivity activity;
    private final SecureStore store;
    private TvPairingServer server;
    private AlertDialog qrDialog;
    TvPairingUi(MainActivity activity, SecureStore store) {
        this.activity=activity;this.store=store;
    }

    void startTv() {
        if(!TvMode.isTv(activity)) {
            error("TV에서 QR 연결을 시작해 주세요.");return;
        }
        close();
        try {
            server=new TvPairingServer(decoded -> activity.runOnUiThread(() -> {
                if(activity.isFinishing() || activity.isDestroyed())return;
                if(qrDialog!=null)qrDialog.dismiss();
                try {
                    org.json.JSONObject payload=new org.json.JSONObject(
                            new String(decoded,java.nio.charset.StandardCharsets.UTF_8));
                    if(SettingsBackup.KIND.equals(payload.optString("kind"))) {
                        showFullSettingsRequest(payload);
                        return;
                    }
                    List<Models.RemoteProfile> profiles=TvPairingCodec.decodeProfiles(decoded);
                StringBuilder labels=new StringBuilder();
                for(Models.RemoteProfile p:profiles)
                    labels.append("• ").append(p.label()).append(" (")
                            .append(p.protocol).append(")\n");
                new AlertDialog.Builder(activity)
                        .setTitle(L10n.t("TV 계정 연결 요청 · ") + profiles.size() + "개")
                        .setMessage(L10n.t("이 TV에 다음 연결을 가져올까요?\n\n") + labels +
                                "\n기존 연결은 유지됩니다.")
                        .setNegativeButton(L10n.t("거절"),null)
                        .setPositiveButton(L10n.t("이 TV에 저장"),(d,which)->importProfiles(profiles))
                        .show();
                }catch(Exception ignored){error("TV로 받은 연결 정보 형식이 올바르지 않습니다.");}
            }));
            String uri=server.uri;
            android.graphics.Bitmap qr=TvQrCode.bitmap(uri,360);
            LinearLayout body=Ui.vertical(activity);
            body.setGravity(Gravity.CENTER);
            body.setPadding(dp(22),dp(12),dp(22),dp(16));
            TextView help=Ui.text(activity,
                    L10n.t("휴대폰 카메라로 QR을 스캔해 FileHub에서 연결할 계정을 선택하세요.\n")+
                    "두 기기는 같은 Wi-Fi에 있어야 합니다.",17,Ui.TEXT,false);
            help.setGravity(Gravity.CENTER);
            body.addView(help,Ui.lp(activity,-1,-2));
            Ui.gap(body,activity,14);
            ImageView qrImage=new ImageView(activity);
            qrImage.setImageBitmap(qr);
            qrImage.setBackgroundColor(Color.WHITE);
            qrImage.setPadding(dp(9),dp(9),dp(9),dp(9));
            body.addView(qrImage,Ui.lp(activity,300,300));
            TvPairingCodec.Destination target=TvPairingCodec.parseUri(uri);
            TextView address=Ui.text(activity,
                    "TV " + target.host + ":" + target.port +
                    "  |  2분 유효 · 일회용",14,Ui.MUTED,false);
            address.setGravity(Gravity.CENTER);
            body.addView(address,Ui.lp(activity,-1,-2));
            qrDialog=new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("FileHub TV · QR로 로그인"))
                    .setView(body)
                    .setNegativeButton(L10n.t("취소"),null).create();
            TvPairingServer active=server;
            qrDialog.setOnDismissListener(d->{if(server==active)close();});
            qrDialog.show();
            server.start();
        }catch(Exception e){close();error("TV 연결을 시작하지 못했습니다. 같은 Wi-Fi를 확인하세요.");}
    }

    private void importProfiles(List<Models.RemoteProfile> profiles) {
        try {
            List<Models.RemoteProfile> saved=store.remotes();
            int added=0;
            for(Models.RemoteProfile incoming:profiles) {
                boolean exists=false;
                for(Models.RemoteProfile prior:saved) {
                    if(prior.protocol.equals(incoming.protocol) &&
                            prior.host.equals(incoming.host) &&
                            prior.port==incoming.port &&
                            prior.username.equals(incoming.username) &&
                            prior.root.equals(incoming.root)) {exists=true;break;}
                }
                if(exists)continue;
                store.putRemote(incoming);
                saved.add(incoming);
                added++;
            }
            new AlertDialog.Builder(activity).setTitle(L10n.t("TV 연결 저장 완료"))
                    .setMessage(""+added+"개 연결을 암호화 저장했습니다.\n"+
                            "Synology DSM 2단계 인증은 이 TV에서 별도로 확인할 수 있습니다.")
                    .setPositiveButton(L10n.t("확인"),null).show();
        }catch(Exception e){error("TV 저장에 실패했습니다. 저장소 접근 권한을 확인하세요.");}
    }

    private void showFullSettingsRequest(org.json.JSONObject snapshot) {
        try{SettingsBackup.validate(snapshot);}
        catch(Exception invalid){error("TV로 받은 설정이 올바르지 않습니다.");return;}
        int count=snapshot.optJSONArray("remotes").length();
        int favorite=snapshot.optJSONArray("favorites").length();
        new AlertDialog.Builder(activity).setTitle(L10n.t("휴대폰 전체 설정 가져오기"))
                .setMessage(L10n.t("연결 ") + count + "개, 즐겨찾기 " + favorite+
                        "개 및 동영상·만화책·소설책 설정이 도착했습니다.\n\n"+
                        "병합: 현재 TV 설정 유지 + 휴대폰 설정 반영\n"+
                        "교체: TV 설정을 휴대폰 설정으로 변경\n\n"+
                        "SD 권한과 외부 코덱 파일은 TV에서 별도로 지정해야 합니다.")
                .setNegativeButton(L10n.t("거절"),null)
                .setNeutralButton(L10n.t("병합"),(d,w)->applyFullSettings(snapshot,false))
                .setPositiveButton(L10n.t("전체 교체"),(d,w)->new AlertDialog.Builder(activity)
                        .setTitle(L10n.t("TV 전체 설정 교체"))
                        .setMessage(L10n.t("기존 설정을 교체할까요? 현재 설정은 사라질 수 있습니다."))
                        .setNegativeButton(L10n.t("취소"),null)
                        .setPositiveButton(L10n.t("교체"),(confirm,yes)->applyFullSettings(snapshot,true))
                        .show()).show();
    }

    private void applyFullSettings(org.json.JSONObject snapshot,boolean replace) {
        new Thread(()->{
            boolean success=false;
            try{SettingsBackup.restore(activity,store,snapshot,replace);success=true;}
            catch(Exception ignored){}
            boolean done=success;
            activity.runOnUiThread(()->{
                if(activity.isFinishing())return;
                new AlertDialog.Builder(activity)
                        .setTitle(done?"TV 설정 복원 완료":"TV 설정 복원 실패")
                        .setMessage(done?"휴대폰 설정이 저장됐습니다. 앱을 다시 실행하면 반영됩니다.":
                                "일부 설정을 저장하지 못했습니다. 현재 설정을 확인하세요.")
                        .setPositiveButton(L10n.t("확인"),null).show();
            });
        },"filehub-tv-restore").start();
    }

    void sendFromPhone(String scanned) {
        if(TvMode.isTv(activity)) {error("휴대폰 FileHub에서 진행해 주세요.");return;}
        List<Models.RemoteProfile> available;
        try {available=store.activeRemotes();}
        catch(Exception e){error("연결 목록을 불러오지 못했습니다.");return;}
        if(available.isEmpty()){
            error("먼저 휴대폰 FileHub에서 NAS 또는 클라우드 계정을 연결하세요.");return;
        }
        LinearLayout header=Ui.vertical(activity);
        header.setPadding(dp(20),dp(8),dp(20),dp(8));
        TextView notice=Ui.text(activity,
                L10n.t("TV QR을 휴대폰 카메라로 스캔해 열거나, QR 문자열을 복사해 아래에 붙여넣으세요.\n")+
                "전송할 연결만 선택하세요. QR은 2분간 유효합니다.",13,Ui.TEXT,false);
        header.addView(notice,Ui.lp(activity,-1,-2));
        Ui.gap(header,activity,8);
        EditText uriInput=new EditText(activity);
        uriInput.setHint(BuildConfig.PAIR_SCHEME + "://pair?...");
        uriInput.setSingleLine(true);
        uriInput.setInputType(InputType.TYPE_CLASS_TEXT|
                InputType.TYPE_TEXT_VARIATION_URI);
        uriInput.setText(scanned);
        header.addView(uriInput,Ui.lp(activity,-1,52));
        TextView choose=Ui.text(activity,L10n.t("전송할 연결 선택 · 최대 12개"),14,Ui.TEXT,true);
        header.addView(choose,Ui.lp(activity,-1,40));
        String[] labels=new String[available.size()];
        boolean[] chosen=new boolean[available.size()];
        for(int i=0;i<available.size();i++)
            labels[i]=available.get(i).label()+" · "+available.get(i).protocol;
        new AlertDialog.Builder(activity).setCustomTitle(header)
                .setMultiChoiceItems(labels,chosen,(d,index,checked)->chosen[index]=checked)
                .setNegativeButton(L10n.t("취소"),null)
                .setNeutralButton(L10n.t("전체 설정 TV로 보내기"),(d,w)->{
                    final TvPairingCodec.Destination destination;
                    try{destination=TvPairingCodec.parseUri(uriInput.getText().toString());}
                    catch(Exception invalid){error("TV QR 주소를 먼저 확인해 주세요.");return;}
                    new AlertDialog.Builder(activity)
                            .setTitle(L10n.t("전체 설정을 TV로 전송할까요?"))
                            .setMessage(L10n.t("대상 TV: ")+destination.host+":"+destination.port+
                                    "\n\n네트워크 연결·계정·즐겨찾기·콘텐츠 폴더·재생 및 독서 " +
                                    "설정을 일회용 QR 키로 암호화해 전송합니다. " +
                                    "TV에서 최종 승인해야 저장됩니다. 본인의 TV인지 확인하세요.")
                            .setNegativeButton(L10n.t("취소"),null)
                            .setPositiveButton(L10n.t("전체 설정 전송"),(confirm,ok)->{
                                new Thread(()->{
                                    boolean success=false;
                                    try {
                                        byte[] plain=SettingsBackup.snapshot(activity,store)
                                                .toString().getBytes(
                                                        java.nio.charset.StandardCharsets.UTF_8);
                                        TvPairingServer.send(destination,plain);
                                        java.util.Arrays.fill(plain,(byte)0);
                                        success=true;
                                    }catch(Exception ignored){}
                                    boolean done=success;
                                    activity.runOnUiThread(()->{
                                        if(activity.isFinishing())return;
                                        new AlertDialog.Builder(activity)
                                                .setTitle(done?"TV로 전체 설정 전송 완료":"TV 전송 실패")
                                                .setMessage(done?"TV에서 병합 또는 전체 교체를 선택하세요.":
                                                        "TV와 같은 Wi-Fi인지, QR이 유효한지 확인하세요.")
                                                .setPositiveButton(L10n.t("확인"),null).show();
                                    });
                                },"filehub-tv-full-settings-send").start();
                            }).show();
                })
                .setPositiveButton(L10n.t("선택한 연결 TV로 보내기"),(d,w)->{
                    List<Models.RemoteProfile> selected=new ArrayList<>();
                    for(int i=0;i<chosen.length;i++)if(chosen[i])selected.add(available.get(i));
                    final TvPairingCodec.Destination destination;
                    final byte[] data;
                    try {
                        destination=TvPairingCodec.parseUri(uriInput.getText().toString());
                        data=TvPairingCodec.encodeProfiles(selected);
                    }catch(Exception bad){error("QR 주소 또는 연결 선택을 확인하세요.");return;}
                    new AlertDialog.Builder(activity)
                            .setTitle(L10n.t("TV에 연결 정보를 보낼까요?"))
                            .setMessage(L10n.t("대상 TV: ") + destination.host + ":" + destination.port+
                                    "\n선택한 연결 "+selected.size()+"개\n\n"+
                                    "NAS 비밀번호와 클라우드 세션이 포함될 수 있으며, " +
                                    "일회용 키로 암호화됩니다. 본인의 TV인지 확인하세요.")
                            .setNegativeButton(L10n.t("취소"),null)
                            .setPositiveButton(L10n.t("암호화 전송"),(confirm,ok)->{
                                new Thread(()->{
                                    boolean success=false;
                                    try{TvPairingServer.send(destination,data);success=true;}
                                    catch(Exception ignored){}
                                    boolean done=success;
                                    activity.runOnUiThread(()->{
                                        if(activity.isFinishing())return;
                                        new AlertDialog.Builder(activity)
                                                .setTitle(done?"TV로 전송 완료":"TV 연결 실패")
                                                .setMessage(done ?
                                                        "TV 화면에서 가져올 연결을 확인하고 저장을 승인하세요." :
                                                        "같은 Wi-Fi인지, 2분 제한이 지났는지, TV QR 화면이 열려 있는지 확인하세요.")
                                                .setPositiveButton(L10n.t("확인"),null).show();
                                    });
                                },"filehub-tv-send").start();
                            }).show();
                }).show();
    }

    private int dp(int value){return Ui.dp(activity,value);}
    private void error(String msg){
        if(activity.isFinishing())return;
        new AlertDialog.Builder(activity).setTitle("FileHub TV")
                .setMessage(msg).setPositiveButton(L10n.t("확인"),null).show();
    }

    @Override public void close(){
        if(server!=null){server.close();server=null;}
    }
}
