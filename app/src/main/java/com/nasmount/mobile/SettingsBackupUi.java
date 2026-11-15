package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.Gravity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Android Storage Access Framework export/import, no global storage permission. */
final class SettingsBackupUi {
    static final int EXPORT_REQUEST=8201;
    static final int IMPORT_REQUEST=8202;
    private final Activity activity;
    private final SecureStore store;
    private final Runnable onRestored;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ExecutorService qrExecutor=Executors.newCachedThreadPool();
    private char[] pendingPassword;
    private boolean busy;
    private LanSettingsTransfer.Receiver lanReceiver;
    private LanSettingsTransfer.Discovery lanDiscovery;
    private AlertDialog lanDialog;
    private int lanSession;
    private AlertDialog qrDialog;
    private volatile InternetQrTransfer.Session receivingQr;
    private volatile int qrGeneration;
    private static final String QR_PREFS="filehub_internet_qr";
    private static final String RELAY_URL="relay_https_url";

    SettingsBackupUi(Activity activity,SecureStore store,Runnable onRestored) {
        this.activity=activity;this.store=store;this.onRestored=onRestored;
    }

    void show() {
        // Same-LAN automatic discovery, cross-network encrypted QR, and offline
        // file backups are distinct operations with separate entry points.
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout content=Ui.vertical(activity);
        content.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),
                Ui.dp(activity,16),Ui.dp(activity,10));
        scroll.addView(content);
        TextView help=Ui.text(activity,
                "같은 네트워크라면 LAN 자동 전송, 다른 네트워크라면 인터넷 QR 전송을 이용하세요.",
                13,Ui.MUTED,false);
        content.addView(help,Ui.lp(activity,-1,-2));
        Ui.gap(content,activity,15);

        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("설정 이동 및 백업")
                .setView(scroll)
                .setNegativeButton("닫기",null).create();

        TransferSection device=addTransferSection(content,"remote", "LAN 자동 전송",
                "같은 Wi-Fi · 기기 자동 검색",false);
        Ui.gap(content,activity,11);
        TransferSection internet=addTransferSection(content,"remote","인터넷 QR 전송",
                "다른 네트워크 · 공개 HTTPS 중계 서버 필요",false);
        Ui.gap(content,activity,11);
        TransferSection file=addTransferSection(content,"drive","파일 백업 · 복원",
                "암호화 파일로 저장하고 가져오기",false);

        device.header.setOnClickListener(v->{
            boolean opening=!device.isOpen();
            device.setOpen(opening);
            if(opening){internet.setOpen(false);file.setOpen(false);}
        });
        internet.header.setOnClickListener(v->{
            boolean opening=!internet.isOpen();
            internet.setOpen(opening);
            if(opening){device.setOpen(false);file.setOpen(false);}
        });
        file.header.setOnClickListener(v->{
            boolean opening=!file.isOpen();
            file.setOpen(opening);
            if(opening){device.setOpen(false);internet.setOpen(false);}
        });

        addTwoActions(device.details,
                "내보내기",()->{dialog.dismiss();startLanExport();},
                "가져오기",()->{dialog.dismiss();startLanImport();});
        addTwoActions(internet.details,
                "QR 표시 · 받기",()->{dialog.dismiss();startInternetQrReceive();},
                "QR 스캔 · 보내기",()->{dialog.dismiss();showInternetQrSend("");});
        TextView relay=Ui.text(activity,"중계 서버 HTTPS 주소 설정 (받는 기기)  ›",
                13,Ui.TEAL,false);
        relay.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        relay.setPadding(Ui.dp(activity,7),Ui.dp(activity,7),Ui.dp(activity,7),
                Ui.dp(activity,7));
        relay.setFocusable(true);
        relay.setOnClickListener(v->{dialog.dismiss();editRelayUrl(null);});
        internet.details.addView(relay,Ui.lp(activity,-1,43));
        addTwoActions(file.details,
                "파일로 저장",()->{dialog.dismiss();askExportPassword();},
                "파일 불러오기",()->{dialog.dismiss();pickImportFile();});
        Ui.gap(file.details,activity,8);
        dialog.show();
    }

    private String savedRelayUrl() {
        return activity.getSharedPreferences(QR_PREFS,Activity.MODE_PRIVATE)
                .getString(RELAY_URL,"");
    }

    private void editRelayUrl(Runnable afterSave) {
        EditText input=new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT|
                InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(savedRelayUrl());
        input.setHint("https://relay.example.com");
        LinearLayout box=Ui.vertical(activity);
        box.setPadding(Ui.dp(activity,18),Ui.dp(activity,8),
                Ui.dp(activity,18),Ui.dp(activity,7));
        box.addView(Ui.text(activity,
                "받는 TV에서 한 번만 설정하세요. 인터넷에 공개된 본인 소유 HTTPS 중계 " +
                "서버가 필요합니다. 휴대폰은 QR에서 서버 주소를 자동으로 읽습니다.",
                13,Ui.TEXT,false),Ui.lp(activity,-1,-2));
        Ui.gap(box,activity,10);
        box.addView(input,Ui.lp(activity,-1,55));
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("인터넷 QR 중계 서버")
                .setView(box)
                .setNegativeButton("취소",null)
                .setPositiveButton("주소 저장",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                String url=InternetQrTransfer.validateEndpoint(
                        input.getText().toString());
                activity.getSharedPreferences(QR_PREFS,Activity.MODE_PRIVATE)
                        .edit().putString(RELAY_URL,url).apply();
                dialog.dismiss();
                if(afterSave!=null)afterSave.run();
            }catch(Exception invalid) {
                input.setError("https://도메인 형식으로 입력하세요.");
            }
        }));
        dialog.show();
    }

    private void startInternetQrReceive() {
        if(savedRelayUrl().isEmpty()){
            editRelayUrl(this::startInternetQrReceive);
            return;
        }
        stopInternetQr();
        final int generation=qrGeneration;
        final InternetQrTransfer.Session session;
        try {session=InternetQrTransfer.newSession(savedRelayUrl());}
        catch(Exception invalid){editRelayUrl(null);return;}
        receivingQr=session;
        AlertDialog connecting=new AlertDialog.Builder(activity)
                .setTitle("인터넷 QR 준비 중")
                .setMessage("설정한 HTTPS 중계 서버와 연결하고 있습니다.")
                .setNegativeButton("취소",(d,w)->stopInternetQr()).create();
        qrDialog=connecting;
        connecting.setOnDismissListener(d->{
            if(qrDialog==connecting && generation==qrGeneration)
                stopInternetQr();
        });
        connecting.show();
        qrExecutor.execute(()->{
            long deadline=System.currentTimeMillis()+InternetQrTransfer.LIFETIME_MS;
            String failure=null;
            boolean delivered=false;
            try {
                InternetQrTransfer.create(session);
                if(generation!=qrGeneration)return;
                activity.runOnUiThread(()->showInternetQr(session,generation));
                while(generation==qrGeneration && System.currentTimeMillis()<deadline) {
                    byte[] plain=InternetQrTransfer.poll(session);
                    if(plain!=null){
                        org.json.JSONObject snapshot;
                        try {
                            snapshot=new org.json.JSONObject(new String(
                                    plain,StandardCharsets.UTF_8));
                            SettingsBackup.validate(snapshot);
                        }finally{Arrays.fill(plain,(byte)0);}
                        delivered=true;
                        activity.runOnUiThread(()->{
                            if(generation!=qrGeneration || activity.isFinishing())return;
                            stopInternetQr();
                            previewRestore(snapshot);
                        });
                        break;
                    }
                    Thread.sleep(1400);
                }
                if(!delivered && generation==qrGeneration)
                    failure="QR 유효시간 2분이 지났습니다. 새로운 QR을 만드세요.";
            }catch(Exception e) {
                failure="인터넷 QR 수신에 실패했습니다. 공개 HTTPS 주소, " +
                        "서버 동작 상태 및 네트워크를 확인하세요.";
            }finally{
                if(generation==qrGeneration && !delivered) {
                    String message=failure;
                    activity.runOnUiThread(()->{
                        if(generation!=qrGeneration)return;
                        stopInternetQr();
                        if(!activity.isFinishing())alert(message);
                    });
                }
                if(!delivered)InternetQrTransfer.cancel(session);
                session.clear();
            }
        });
    }

    private void showInternetQr(InternetQrTransfer.Session session,int generation) {
        if(generation!=qrGeneration || activity.isFinishing())return;
        try {
            Bitmap code=TvQrCode.bitmap(session.qrUri(),440);
            LinearLayout body=Ui.vertical(activity);
            body.setGravity(Gravity.CENTER);
            body.setPadding(Ui.dp(activity,12),Ui.dp(activity,8),
                    Ui.dp(activity,12),Ui.dp(activity,12));
            body.addView(Ui.text(activity,
                    "휴대폰 기본 카메라로 QR을 스캔한 뒤 웹페이지에서 " +
                    "'FileHub에서 열기'를 누르고 전송을 승인하세요."+
                    "\n두 기기가 다른 Wi-Fi여도 가능합니다. 2분간 유효합니다.",
                    13,Ui.TEXT,false),Ui.lp(activity,-1,-2));
            Ui.gap(body,activity,12);
            ImageView qr=new ImageView(activity);
            qr.setImageBitmap(code);
            qr.setBackgroundColor(Color.WHITE);
            qr.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),
                    Ui.dp(activity,8),Ui.dp(activity,8));
            LinearLayout.LayoutParams imageSize=Ui.lp(activity,260,260);
            imageSize.gravity=Gravity.CENTER;
            body.addView(qr,imageSize);
            Ui.gap(body,activity,9);
            TextView check=Ui.text(activity,
                    "확인 코드: "+session.invitation.shortCode(),18,Ui.TEAL,true);
            check.setGravity(Gravity.CENTER);
            body.addView(check,Ui.lp(activity,-1,-2));
            AlertDialog open=new AlertDialog.Builder(activity)
                    .setTitle("인터넷 QR · 설정 가져오기")
                    .setView(body)
                    .setNegativeButton("수신 취소",(d,w)->stopInternetQr()).create();
            open.setOnDismissListener(d->{
                if(qrDialog==open && generation==qrGeneration)
                    stopInternetQr();
            });
            AlertDialog previous=qrDialog;
            qrDialog=open;
            if(previous!=null){
                previous.setOnDismissListener(null);
                previous.dismiss();
            }
            open.show();
        }catch(Exception failed){stopInternetQr();alert("QR 화면을 만들지 못했습니다.");}
    }

    private void stopInternetQr() {
        qrGeneration++;
        InternetQrTransfer.Session session=receivingQr;
        receivingQr=null;
        AlertDialog open=qrDialog;
        qrDialog=null;
        if(open!=null){
            open.setOnDismissListener(null);
            open.dismiss();
        }
        if(session!=null && !qrExecutor.isShutdown())
            qrExecutor.execute(()->InternetQrTransfer.cancel(session));
    }

    /** The Android camera opens a filehubaio://relay deep link, not a web page. */
    void showInternetQrSend(String scannedUri) {
        if(activity.isFinishing())return;
        EditText qrInput=new EditText(activity);
        qrInput.setText(scannedUri==null?"":scannedUri);
        qrInput.setSingleLine(true);
        qrInput.setInputType(InputType.TYPE_CLASS_TEXT|
                InputType.TYPE_TEXT_VARIATION_URI);
        qrInput.setHint("https://relay.example.com/pair#...");
        LinearLayout body=Ui.vertical(activity);
        body.setPadding(Ui.dp(activity,18),Ui.dp(activity,6),
                Ui.dp(activity,18),Ui.dp(activity,9));
        body.addView(Ui.text(activity,
                "받는 TV가 보여주는 QR을 휴대폰 카메라로 스캔하세요. " +
                "브라우저에서 'FileHub에서 열기'를 누릅니다. " +
                "링크가 자동으로 열리지 않으면 QR 주소를 붙여넣으세요."+
                "\n\n설정에는 NAS 비밀번호나 클라우드 토큰이 포함될 수 있으니 " +
                "본인의 TV와 확인 코드를 반드시 대조하세요.",
                13,Ui.TEXT,false),Ui.lp(activity,-1,-2));
        Ui.gap(body,activity,12);
        body.addView(qrInput,Ui.lp(activity,-1,58));
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("인터넷 QR · TV로 설정 보내기")
                .setView(body)
                .setNegativeButton("취소",null)
                .setPositiveButton("TV 확인",null).create();
        dialog.setOnShowListener(d->dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            final InternetQrTransfer.Invitation invite;
            try{invite=InternetQrTransfer.parseQr(qrInput.getText().toString());}
            catch(Exception bad){qrInput.setError("TV의 인터넷 QR을 다시 확인하세요.");return;}
            dialog.dismiss();
            new AlertDialog.Builder(activity)
                    .setTitle("TV 확인 · "+invite.shortCode())
                    .setMessage("TV QR 확인 코드가 위 번호와 같나요?\n\n중계 서버: "+
                            invite.endpoint+"\n\n네트워크·계정 비밀번호·즐겨찾기·" +
                            "재생/독서 설정을 종단 간 암호화해 전송합니다. " +
                            "TV에서 다시 병합·교체를 승인해야 저장됩니다.")
                    .setNegativeButton("취소",(cancel,w)->invite.clear())
                    .setPositiveButton("암호화 전송",(send,w)->sendInternetQr(invite))
                    .show();
        }));
        dialog.show();
    }

    private void sendInternetQr(InternetQrTransfer.Invitation invitation) {
        if(busy){invitation.clear();alert("현재 다른 설정 전송 작업이 진행 중입니다.");return;}
        busy=true;
        AlertDialog sending=new AlertDialog.Builder(activity)
                .setTitle("인터넷 QR 설정 전송")
                .setMessage("일회용 QR 키로 설정을 암호화해 보내고 있습니다.")
                .setCancelable(false).create();
        sending.show();
        qrExecutor.execute(()->{
            boolean success=false;
            byte[] plain=null;
            try{
                plain=SettingsBackup.snapshot(activity,store).toString()
                        .getBytes(StandardCharsets.UTF_8);
                InternetQrTransfer.send(invitation,plain);
                success=true;
            }catch(Exception ignored){}
            finally {
                if(plain!=null)Arrays.fill(plain,(byte)0);
                invitation.clear();
            }
            boolean done=success;
            activity.runOnUiThread(()->{
                busy=false;
                sending.dismiss();
                if(activity.isFinishing())return;
                alert(done?"TV로 암호화 전송을 완료했습니다.\n" +
                        "TV에서 병합 또는 전체 교체를 승인하세요.":
                        "QR 전송에 실패했습니다. QR 2분 제한, HTTPS 서버 " +
                        "연결 및 확인 코드를 점검해 주세요.");
            });
        });
    }

    private static final class TransferSection {
        final LinearLayout header,details;
        final TextView arrow;
        TransferSection(LinearLayout header,LinearLayout details,TextView arrow) {
            this.header=header;
            this.details=details;
            this.arrow=arrow;
        }
        boolean isOpen(){return details.getVisibility()==View.VISIBLE;}
        void setOpen(boolean open){
            details.setVisibility(open?View.VISIBLE:View.GONE);
            arrow.setText(open?"▴":"▾");
            header.setContentDescription(open?"펼쳐진 설정 메뉴":"접힌 설정 메뉴");
        }
    }

    private TransferSection addTransferSection(LinearLayout parent,String icon,
                                                String title,String subtitle,
                                                boolean expanded) {
        LinearLayout card=Ui.vertical(activity);
        card.setBackground(Ui.bg(activity,Ui.SURFACE,14,Ui.BORDER));
        parent.addView(card,Ui.lp(activity,-1,-2));
        LinearLayout header=Ui.horizontal(activity);
        header.setPadding(Ui.dp(activity,13),Ui.dp(activity,14),
                Ui.dp(activity,13),Ui.dp(activity,14));
        header.setClickable(true);
        header.setFocusable(true);
        card.addView(header,Ui.lp(activity,-1,-2));

        Ui.Icon symbol=new Ui.Icon(activity,icon,Ui.TEAL);
        header.addView(symbol,Ui.lp(activity,32,32));
        LinearLayout labels=Ui.vertical(activity);
        labels.setPadding(Ui.dp(activity,11),0,0,0);
        header.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        labels.addView(Ui.text(activity,title,16,Ui.TEXT,true),Ui.lp(activity,-1,-2));
        Ui.gap(labels,activity,5);
        labels.addView(Ui.text(activity,subtitle,12,Ui.MUTED,false),Ui.lp(activity,-1,-2));
        TextView arrow=Ui.text(activity,"▾",22,Ui.MUTED,false);
        arrow.setGravity(Gravity.CENTER);
        header.addView(arrow,Ui.lp(activity,25,35));

        LinearLayout details=Ui.vertical(activity);
        details.setPadding(Ui.dp(activity,13),0,Ui.dp(activity,13),Ui.dp(activity,5));
        card.addView(details,Ui.lp(activity,-1,-2));
        TransferSection section=new TransferSection(header,details,arrow);
        section.setOpen(expanded);
        return section;
    }

    private void addTwoActions(LinearLayout target,String first,Runnable actionOne,
                               String second,Runnable actionTwo) {
        LinearLayout line=Ui.horizontal(activity);
        target.addView(line,Ui.lp(activity,-1,49));
        TextView left=Ui.action(activity,first);
        TextView right=Ui.action(activity,second);
        left.setFocusable(true);
        right.setFocusable(true);
        LinearLayout.LayoutParams leftParams=new LinearLayout.LayoutParams(0,
                Ui.dp(activity,47),1);
        leftParams.rightMargin=Ui.dp(activity,6);
        line.addView(left,leftParams);
        LinearLayout.LayoutParams rightParams=new LinearLayout.LayoutParams(0,
                Ui.dp(activity,47),1);
        rightParams.leftMargin=Ui.dp(activity,6);
        line.addView(right,rightParams);
        left.setOnClickListener(v->actionOne.run());
        right.setOnClickListener(v->actionTwo.run());
    }

    private void startLanImport() {
        closeLan();
        final int currentSession=lanSession;
        try {
            lanReceiver=new LanSettingsTransfer.Receiver(snapshot ->
                    activity.runOnUiThread(()->{
                        if(currentSession!=lanSession)return;
                        if(activity.isFinishing() || activity.isDestroyed())return;
                        if(lanDialog!=null)lanDialog.dismiss();
                        previewRestore(snapshot);
                    }),()->activity.runOnUiThread(()->{
                if(currentSession!=lanSession)return;
                if(lanDialog!=null && lanDialog.isShowing())lanDialog.dismiss();
            }));
            AlertDialog dialog=new AlertDialog.Builder(activity)
                    .setTitle("설정 가져오기 대기 중")
                    .setMessage("같은 Wi-Fi의 다른 FileHub에서 '설정 내보내기'를 선택하세요.\n\n"+
                            "TV 또는 휴대폰이 자동으로 검색됩니다.\n"+
                            "2분 동안 대기하며, 수신 후 병합·교체를 직접 승인합니다.\n"+
                            "계정과 비밀번호를 포함하므로 신뢰하는 개인 네트워크에서만 사용하세요.")
                    .setNegativeButton("수신 취소",null).create();
            lanDialog=dialog;
            LanSettingsTransfer.Receiver active=lanReceiver;
            dialog.setOnDismissListener(d->{
                if(lanReceiver==active){active.close();lanReceiver=null;}
                if(lanDialog==dialog)lanDialog=null;
            });
            dialog.show();
            lanReceiver.start();
        }catch(Exception e){closeLan();alert("설정 수신을 시작하지 못했습니다. 네트워크 또는 다른 수신 앱을 확인하세요.");}
    }

    private void startLanExport() {
        closeLan();
        final int currentSession=lanSession;
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("설정 내보내기 · 기기 검색 중")
                .setMessage("같은 네트워크에서 '설정 가져오기 대기' 중인 FileHub를 찾고 있습니다.\n"+
                            "대상 기기는 1대면 자동 연결, 여러 대면 선택합니다.\n\n"+
                            "개인 Wi-Fi에서만 사용하고, 받는 기기에서 최종 승인을 확인하세요.")
                .setNegativeButton("검색 취소",null).create();
        lanDialog=dialog;
        lanDiscovery=new LanSettingsTransfer.Discovery(devices ->
                activity.runOnUiThread(()->{
                    if(currentSession!=lanSession)return;
                    if(activity.isFinishing() || activity.isDestroyed())return;
                    if(lanDialog==dialog && dialog.isShowing())dialog.dismiss();
                    if(devices.isEmpty()){
                        alert("가져오기 대기 중인 FileHub가 검색되지 않았습니다.\n\n"+
                                "같은 Wi-Fi인지, 받는 기기에서 먼저 '설정 가져오기'를 눌렀는지, " +
                                "공유기 AP 격리 설정이 꺼져 있는지 확인하세요.");
                        return;
                    }
                    if(devices.size()==1){confirmLanRecipient(devices.get(0));return;}
                    String[] names=new String[devices.size()];
                    for(int i=0;i<devices.size();i++)names[i]=devices.get(i).label();
                    new AlertDialog.Builder(activity)
                            .setTitle("가져올 기기 선택")
                            .setItems(names,(d,index)->confirmLanRecipient(devices.get(index)))
                            .setNegativeButton("취소",null).show();
                }));
        LanSettingsTransfer.Discovery searching=lanDiscovery;
        dialog.setOnDismissListener(d->{
            if(lanDiscovery==searching){searching.close();lanDiscovery=null;}
            if(lanDialog==dialog)lanDialog=null;
        });
        dialog.show();
        lanDiscovery.start();
    }

    private void confirmLanRecipient(LanSettingsTransfer.Offer device) {
        // Unauthenticated LAN discovery must not send credentials silently.
        new AlertDialog.Builder(activity)
                .setTitle("이 기기로 설정을 보낼까요?")
                .setMessage("발견된 기기: " + device.label() + "\n\n"+
                        "NAS 비밀번호와 클라우드 토큰이 포함될 수 있습니다. " +
                        "본인의 TV/휴대폰인지 확인해 주세요.\n\n"+
                        "기기 탐색과 연결은 자동으로 처리됩니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("암호화 전송",(d,w)->sendLanSettings(device))
                .show();
    }

    private void sendLanSettings(LanSettingsTransfer.Offer device) {
        if(busy)return;
        busy=true;
        AlertDialog sending=new AlertDialog.Builder(activity)
                .setTitle("설정 자동 전송 중")
                .setMessage("대상: " + device.label()+"\n\n"+
                        "네트워크 연결·즐겨찾기·재생·독서 설정을 암호화해 전송하고 있습니다.")
                .setCancelable(false).create();
        sending.show();
        executor.execute(()->{
            boolean success=false;
            try {
                byte[] payload=SettingsBackup.snapshot(activity,store).toString()
                        .getBytes(StandardCharsets.UTF_8);
                try{LanSettingsTransfer.send(device,payload);success=true;}
                finally{Arrays.fill(payload,(byte)0);}
            }catch(Exception ignored){}
            boolean done=success;
            activity.runOnUiThread(()->{
                busy=false;sending.dismiss();
                alert(done?"설정 전송 완료.\n받는 기기에서 병합 또는 전체 교체를 승인하면 적용됩니다.":
                        "전송에 실패했습니다. 두 기기가 같은 네트워크인지 확인하고 다시 시도하세요.");
            });
        });
    }

    private void closeLan() {
        lanSession++;
        if(lanReceiver!=null){lanReceiver.close();lanReceiver=null;}
        if(lanDiscovery!=null){lanDiscovery.close();lanDiscovery=null;}
        if(lanDialog!=null){AlertDialog open=lanDialog;lanDialog=null;open.dismiss();}
    }

    private EditText passwordInput() {
        EditText input=new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT|
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setHint("8자 이상 별도 백업 비밀번호");
        input.setPadding(Ui.dp(activity,18),Ui.dp(activity,8),
                Ui.dp(activity,18),Ui.dp(activity,8));
        return input;
    }

    private void askExportPassword() {
        LinearLayout layout=Ui.vertical(activity);
        TextView description=Ui.text(activity,
                "NAS 비밀번호·클라우드 토큰·즐겨찾기·콘텐츠 폴더·재생·독서 설정을 " +
                "AES-256으로 암호화해 저장합니다.\n백업 비밀번호를 잃어버리면 복원할 수 없습니다.",
                13,Ui.TEXT,false);
        layout.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,16),0);
        layout.addView(description,Ui.lp(activity,-1,-2));
        EditText password=passwordInput();
        layout.addView(password,Ui.lp(activity,-1,60));
        EditText again=passwordInput();
        again.setHint("백업 비밀번호 다시 입력");
        layout.addView(again,Ui.lp(activity,-1,60));
        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("암호화 설정 백업")
                .setView(layout)
                .setNegativeButton("취소",null)
                .setPositiveButton("내보낼 위치 선택",null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            char[] one=password.getText().toString().toCharArray();
            char[] two=again.getText().toString().toCharArray();
            try {
                if(one.length<8 || one.length>128 || !Arrays.equals(one,two)) {
                    alert("백업 비밀번호는 8자 이상이어야 하며 두 입력이 같아야 합니다.");
                    return;
                }
                clearPending();
                pendingPassword=one;
                one=null;
                dialog.dismiss();
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/octet-stream");
                intent.putExtra(Intent.EXTRA_TITLE,
                        "FileHub_AIO_Settings_"+new java.text.SimpleDateFormat(
                                "yyyyMMdd_HHmm",java.util.Locale.US)
                                .format(new java.util.Date())+".fhbackup");
                activity.startActivityForResult(intent,EXPORT_REQUEST);
            }catch(Exception e) {clearPending();alert("백업 파일 선택기를 열지 못했습니다.");}
            finally {if(one!=null)Arrays.fill(one,'\0');Arrays.fill(two,'\0');}
        }));
        dialog.show();
    }

    private void pickImportFile() {
        try {
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            activity.startActivityForResult(intent,IMPORT_REQUEST);
        }catch(Exception e){alert("파일 선택기를 열 수 없습니다. TV에 파일 탐색기 앱이 있는지 확인하세요.");}
    }

    boolean handleResult(int request,int result,Intent data) {
        if(request!=EXPORT_REQUEST && request!=IMPORT_REQUEST)return false;
        if(result!=Activity.RESULT_OK || data==null || data.getData()==null){
            clearPending();return true;
        }
        Uri uri=data.getData();
        if(request==EXPORT_REQUEST){
            char[] password=pendingPassword;pendingPassword=null;
            if(password!=null)doExport(uri,password);
        }else askImportPassword(uri);
        return true;
    }

    private void doExport(Uri destination,char[] password) {
        if(busy){Arrays.fill(password,'\0');return;}
        busy=true;
        executor.execute(()->{
            String failure=null;
            try {
                JSONObject snapshot=SettingsBackup.snapshot(activity,store);
                byte[] plain=snapshot.toString().getBytes(StandardCharsets.UTF_8);
                byte[] encrypted=SettingsBackupCodec.encrypt(plain,password);
                Arrays.fill(plain,(byte)0);
                try(OutputStream output=activity.getContentResolver().openOutputStream(
                        destination,"wt")) {
                    if(output==null)throw new java.io.IOException("파일을 만들 수 없습니다.");
                    output.write(encrypted);output.flush();
                }
                Arrays.fill(encrypted,(byte)0);
            }catch(Exception e){failure="설정 파일을 저장하지 못했습니다: " + e.getClass().getSimpleName();}
            finally{Arrays.fill(password,'\0');}
            String result=failure;
            activity.runOnUiThread(()->{
                busy=false;
                alert(result==null ? "암호화 설정 백업이 저장됐습니다.\n"+
                        "TV로 파일을 옮긴 뒤 같은 비밀번호로 가져오세요." : result);
            });
        });
    }

    private void askImportPassword(Uri uri) {
        EditText pass=passwordInput();
        LinearLayout box=Ui.vertical(activity);
        box.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,16),0);
        box.addView(Ui.text(activity,
                "내보낼 때 설정한 비밀번호를 입력해 주세요. " +
                "내용 확인 후 기존 설정과 병합하거나 교체할 수 있습니다.",
                13,Ui.TEXT,false),Ui.lp(activity,-1,-2));
        box.addView(pass,Ui.lp(activity,-1,65));
        new AlertDialog.Builder(activity).setTitle("설정 백업 열기")
                .setView(box).setNegativeButton("취소",null)
                .setPositiveButton("파일 확인",(d,w)->{
                    char[] password=pass.getText().toString().toCharArray();
                    doImportPreview(uri,password);
                }).show();
    }

    private void doImportPreview(Uri uri,char[] password) {
        if(busy){Arrays.fill(password,'\0');return;}
        busy=true;
        executor.execute(()->{
            JSONObject result=null;
            String failure=null;
            try {
                byte[] encrypted;
                try(InputStream input=activity.getContentResolver().openInputStream(uri)){
                    if(input==null)throw new java.io.IOException("백업 파일을 읽을 수 없습니다.");
                    ByteArrayOutputStream output=new ByteArrayOutputStream();
                    byte[] buffer=new byte[8192];
                    int count;
                    while((count=input.read(buffer))>=0) {
                        if(count==0)continue;
                        if(output.size()+count>SettingsBackupCodec.MAX_FILE_BYTES)
                            throw new IllegalArgumentException("백업 파일이 너무 큽니다.");
                        output.write(buffer,0,count);
                    }
                    encrypted=output.toByteArray();
                }
                byte[] plain=SettingsBackupCodec.decrypt(encrypted,password);
                result=new JSONObject(new String(plain,StandardCharsets.UTF_8));
                Arrays.fill(plain,(byte)0);
                SettingsBackup.validate(result);
            }catch(Exception e){failure="백업 비밀번호 또는 파일 형식을 확인하세요.";}
            finally{Arrays.fill(password,'\0');}
            JSONObject snapshot=result;
            String problem=failure;
            activity.runOnUiThread(()->{
                busy=false;
                if(problem!=null){alert(problem);return;}
                previewRestore(snapshot);
            });
        });
    }

    private void previewRestore(JSONObject snapshot) {
        int remoteCount=snapshot.optJSONArray("remotes").length();
        int favoriteCount=snapshot.optJSONArray("favorites").length();
        new AlertDialog.Builder(activity)
                .setTitle("설정 가져오기 · 내용 확인")
                .setMessage("원격 연결 " + remoteCount + "개, 즐겨찾기 " + favoriteCount+
                        "개 및 동영상·만화책·소설책·사용자 설정을 복원합니다.\n\n" +
                        "기기별 SD카드 접근 권한이나 코덱 파일은 별도로 지정해야 합니다.\n\n" +
                        "병합: 기존 연결을 유지하고 가져온 설정을 반영\n" +
                        "교체: 기존 연결·즐겨찾기·대상 설정을 새 백업으로 변경")
                .setNegativeButton("취소",null)
                .setNeutralButton("병합",(d,w)->confirmRestore(snapshot,false))
                .setPositiveButton("전체 교체",(d,w)->confirmRestore(snapshot,true))
                .show();
    }

    private void confirmRestore(JSONObject snapshot,boolean replace) {
        if(replace) {
            new AlertDialog.Builder(activity).setTitle("전체 설정을 교체할까요?")
                    .setMessage("현재 TV/휴대폰의 연결 및 설정이 백업 내용으로 교체됩니다. " +
                            "필요하면 현재 설정을 먼저 내보내세요.")
                    .setNegativeButton("취소",null)
                    .setPositiveButton("교체 실행",(d,w)->restore(snapshot,true)).show();
        }else restore(snapshot,false);
    }

    private void restore(JSONObject snapshot,boolean replace) {
        if(busy)return;
        busy=true;
        executor.execute(()->{
            String error=null;int count=0;
            try{count=SettingsBackup.restore(activity,store,snapshot,replace);}
            catch(Exception ex){error="설정 복원 오류: " + ex.getClass().getSimpleName();}
            int restored=count;String problem=error;
            activity.runOnUiThread(()->{
                busy=false;
                if(problem!=null){alert(problem);return;}
                alert("연결 " + restored + "개 및 백업 설정 복원 완료.\n" +
                        "일부 화면은 앱을 다시 실행해야 반영됩니다.");
                onRestored.run();
            });
        });
    }

    private void clearPending() {
        if(pendingPassword!=null)Arrays.fill(pendingPassword,'\0');
        pendingPassword=null;
    }

    void shutdown(){
        closeLan();
        stopInternetQr();
        clearPending();
        executor.shutdownNow();
        qrExecutor.shutdownNow();
    }

    private void alert(String text){
        if(activity.isFinishing() || activity.isDestroyed())return;
        new AlertDialog.Builder(activity).setTitle("FileHub 설정 백업")
                .setMessage(text).setPositiveButton("확인",null).show();
    }
}
