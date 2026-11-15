package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
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
    private char[] pendingPassword;
    private boolean busy;
    private LanSettingsTransfer.Receiver lanReceiver;
    private LanSettingsTransfer.Discovery lanDiscovery;
    private AlertDialog lanDialog;
    private int lanSession;

    SettingsBackupUi(Activity activity,SecureStore store,Runnable onRestored) {
        this.activity=activity;this.store=store;this.onRestored=onRestored;
    }

    void show() {
        // Keep the first screen to only two categories; existing transport and
        // encrypted-backup implementations are called unchanged by the buttons.
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout content=Ui.vertical(activity);
        content.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),
                Ui.dp(activity,16),Ui.dp(activity,10));
        scroll.addView(content);
        TextView help=Ui.text(activity,
                "다른 기기로 설정을 옮기거나 백업 파일로 보관할 수 있어요.",
                13,Ui.MUTED,false);
        content.addView(help,Ui.lp(activity,-1,-2));
        Ui.gap(content,activity,15);

        AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("설정 이동 및 백업")
                .setView(scroll)
                .setNegativeButton("닫기",null).create();

        TransferSection device=addTransferSection(content,"remote", "기기 간 설정 이동",
                "같은 Wi-Fi에서 자동으로 연결",false);
        Ui.gap(content,activity,11);
        TransferSection file=addTransferSection(content,"drive","파일 백업 · 복원",
                "암호화 파일로 저장하고 가져오기",false);

        device.header.setOnClickListener(v->{
            boolean opening=!device.isOpen();
            device.setOpen(opening);
            if(opening)file.setOpen(false);
        });
        file.header.setOnClickListener(v->{
            boolean opening=!file.isOpen();
            file.setOpen(opening);
            if(opening)device.setOpen(false);
        });

        addTwoActions(device.details,
                "내보내기",()->{dialog.dismiss();startLanExport();},
                "가져오기",()->{dialog.dismiss();startLanImport();});
        TextView qr=Ui.text(activity,"QR로 연결하기  ›",13,Ui.TEAL,false);
        qr.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        qr.setPadding(Ui.dp(activity,8),Ui.dp(activity,10),Ui.dp(activity,8),
                Ui.dp(activity,7));
        qr.setClickable(true);
        qr.setFocusable(true);
        qr.setContentDescription("기존 QR 방식으로 연결하기");
        qr.setOnClickListener(v->{
            dialog.dismiss();
            if(activity instanceof MainActivity)
                ((MainActivity)activity).startPhoneToTvPairing();
        });
        device.details.addView(qr,Ui.lp(activity,-1,42));

        addTwoActions(file.details,
                "파일로 저장",()->{dialog.dismiss();askExportPassword();},
                "파일 불러오기",()->{dialog.dismiss();pickImportFile();});
        Ui.gap(file.details,activity,8);
        dialog.show();
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
        if(request!=EXPORT_REQUEST && request!=IMPOR