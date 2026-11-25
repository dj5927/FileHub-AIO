package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Nonintrusive startup update hint. Download/install only after user action. */
final class OtaUpdater {
    private final Activity activity;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean busy;
    private volatile boolean closed;
    private volatile OtaReleasePolicy.Release available;
    private File verifiedApk;

    OtaUpdater(Activity activity){this.activity=activity;}

    void startupCheck() {
        // No blocking UI and no toast if there's no newer release/offline.
        check(false);
    }

    void manualCheck() {
        available=null;
        check(true);
    }

    private void check(boolean userRequested) {
        if(closed || busy)return;
        busy=true;
        io.execute(()->{
            OtaReleasePolicy.Release release=null;
            Exception error=null;
            try {
                PrivateOtaChannel channel=PrivateOtaChannel.load(activity);
                HttpURLConnection conn=channel==null ?
                        (HttpURLConnection)new URL(OtaReleasePolicy.API).openConnection() :
                        channel.open(channel.base+"manifest.json");
                conn.setConnectTimeout(6500);
                conn.setReadTimeout(7500);
                conn.setRequestProperty("Accept","application/vnd.github+json");
                conn.setRequestProperty("User-Agent","FileHub-AIO-Android-OTA");
                try {
                    if(conn.getResponseCode()!=200)throw new java.io.IOException("GitHub API unavailable");
                    try(InputStream stream=conn.getInputStream()) {
                        byte[] buffer=new byte[8192];
                        java.io.ByteArrayOutputStream data=new java.io.ByteArrayOutputStream();
                        int n;
                        while((n=stream.read(buffer))!=-1) {
                            if(data.size()+n>250_000)throw new java.io.IOException("Release metadata too large");
                            data.write(buffer,0,n);
                        }
                        JSONObject json=new JSONObject(data.toString("UTF-8"));
                        release=channel==null?OtaReleasePolicy.parse(json):channel.parse(json);
                    }
                }finally {conn.disconnect();}
            }catch(Exception e){error=e;}
            OtaReleasePolicy.Release result=release;
            Exception failure=error;
            main.post(()->{
                busy=false;
                if(closed || activity.isFinishing() || activity.isDestroyed())return;
                if(result!=null && OtaReleasePolicy.isNewer(result,installedVersion())) {
                    available=result;
                    if(userRequested)showRelease(result);
                    else Toast.makeText(activity,L10n.t("FileHub 새 업데이트 있음 · v")+
                            result.version,Toast.LENGTH_SHORT).show();
                }else if(userRequested) {
                    Toast.makeText(activity,failure==null ?
                            "현재 최신 버전입니다." :
                            "업데이트 정보를 확인하지 못했습니다.",Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private long installedVersion() {
        try{
            PackageInfo info=activity.getPackageManager().getPackageInfo(
                    activity.getPackageName(),0);
            return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
        }catch(Exception ignored){return 0;}
    }

    private void showRelease(OtaReleasePolicy.Release release) {
        if(closed || activity.isFinishing())return;
        String notes=OtaNoteSummary.format(release.notes);
        new AlertDialog.Builder(activity)
                .setTitle("FileHub v"+release.version+" 업데이트")
                .setMessage((notes.isEmpty()?"새 버전을 사용할 수 있습니다.":notes)+
                        "\n\n다운로드 크기: "+String.format(Locale.KOREA,"%.1f MB",
                        release.size/1_000_000d))
                .setNegativeButton(L10n.t("나중에"),null)
                .setPositiveButton(L10n.t("다운로드 및 설치"),(d,w)->download(release))
                .show();
    }

    private void download(OtaReleasePolicy.Release release) {
        if(busy || closed)return;
        busy=true;
        ProgressDialog progress=new ProgressDialog(activity);
        progress.setTitle(L10n.t("FileHub 업데이트 다운로드"));
        progress.setMessage(L10n.t("검증 가능한 APK를 받는 중..."));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();
        io.execute(()->{
            File result=null;
            Exception error=null;
            try{
                File folder=new File(activity.getCacheDir(),"ota_updates");
                if(!folder.exists() && !folder.mkdirs())throw new java.io.IOException("Cache unavailable");
                File target=new File(folder,"FileHub-AIO-update.apk");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                PrivateOtaChannel channel=PrivateOtaChannel.load(activity);
                String publicUrl=OtaReleasePolicy.REPO+"/releases/download/v"+
                        release.version+"/FileHub-AIO-v"+release.version+".apk";
                if(channel==null && !publicUrl.equals(release.url))
                    throw new SecurityException("Update channel changed; check again");
                HttpURLConnection connection=channel==null?
                        (HttpURLConnection)new URL(release.url).openConnection():
                        channel.openDownload(release);
                connection.setInstanceFollowRedirects(channel==null);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(25000);
                connection.setRequestProperty("User-Agent","FileHub-AIO-Android-OTA");
                try {
                    if(connection.getResponseCode()!=200)
                        throw new java.io.IOException("APK HTTP "+connection.getResponseCode());
                    long received=0;
                    try(InputStream input=connection.getInputStream();
                        FileOutputStream output=new FileOutputStream(target,false)) {
                        byte[] buffer=new byte[65536];
                        int n;
                        while((n=input.read(buffer))!=-1){
                            received+=n;
                            if(received>release.size)throw new java.io.IOException("APK size mismatch");
                            output.write(buffer,0,n);
                            digest.update(buffer,0,n);
                            int percent=(int)(received*100/release.size);
                            main.post(()->{if(progress.isShowing())progress.setProgress(percent);});
                        }
                    }
                    if(received!=release.size)throw new java.io.IOException("Incomplete APK");
                    StringBuilder hex=new StringBuilder();
                    for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                    if(!hex.toString().equalsIgnoreCase(release.sha256))
                        throw new SecurityException("APK SHA-256 mismatch");
                    verifyPackage(target,release.code);
                    result=target;
                }finally {connection.disconnect();}
            }catch(Exception e){error=e;}
            File completed=result;
            Exception failure=error;
            main.post(()->{
                busy=false;
                progress.dismiss();
                if(closed || activity.isFinishing() || activity.isDestroyed())return;
                if(failure!=null){
                    new AlertDialog.Builder(activity).setTitle(L10n.t("업데이트 실패"))
                            .setMessage(L10n.t("다운로드 또는 APK 검증에 실패했습니다. 다시 시도해 주세요."))
                            .setPositiveButton(L10n.t("확인"),null).show();
                }else{
                    verifiedApk=completed;
                    install(completed);
                }
            });
        });
    }

    private void verifyPackage(File apk,int expectedCode) throws Exception {
        PackageManager pm=activity.getPackageManager();
        int flag=Build.VERSION.SDK_INT>=28 ?
                PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flag);
        PackageInfo current=pm.getPackageInfo(activity.getPackageName(),flag);
        if(archive==null || !activity.getPackageName().equals(archive.packageName))
            throw new SecurityException("Unexpected APK package");
        long actual=Build.VERSION.SDK_INT>=28?archive.getLongVersionCode():archive.versionCode;
        if(actual!=expectedCode || actual<=installedVersion())
            throw new SecurityException("Unexpected APK version");
        android.content.pm.Signature[] expected=Build.VERSION.SDK_INT>=28?
                current.signingInfo.getApkContentsSigners():current.signatures;
        android.content.pm.Signature[] incoming=Build.VERSION.SDK_INT>=28?
                archive.signingInfo.getApkContentsSigners():archive.signatures;
        if(expected==null || incoming==null || expected.length!=incoming.length)
            throw new SecurityException("Signing identity missing");
        java.util.Set<String> signatures=new java.util.HashSet<>();
        for(android.content.pm.Signature item:expected)signatures.add(item.toCharsString());
        for(android.content.pm.Signature item:incoming)
            if(!signatures.remove(item.toCharsString()))
                throw new SecurityException("Unexpected APK signing certificate");
    }

    private void install(File apk) {
        try{
            if(Build.VERSION.SDK_INT>=26 &&
                    !activity.getPackageManager().canRequestPackageInstalls()){
                new AlertDialog.Builder(activity).setTitle(L10n.t("APK 설치 허용 필요"))
                        .setMessage(L10n.t("FileHub에서 다운로드한 업데이트를 설치하려면 ") +
                                "이 출처의 앱 설치를 허용해야 합니다. 허용 후 메뉴에서 업데이트를 다시 선택하세요.")
                        .setNegativeButton(L10n.t("취소"),null)
                        .setPositiveButton(L10n.t("설정 열기"),(d,w)->{
                            Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:"+activity.getPackageName()));
                            activity.startActivity(settings);
                        }).show();
                return;
            }
            Uri uri=FileProvider.getUriForFile(activity,
                    activity.getPackageName()+".files",apk);
            Intent intent=new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri,"application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        }catch(Exception e){
            new AlertDialog.Builder(activity).setTitle(L10n.t("설치 화면 실행 실패"))
                    .setMessage(L10n.t("APK 다운로드는 완료됐으나 설치 화면을 열지 못했습니다."))
                    .setPositiveButton(L10n.t("확인"),null).show();
        }
    }

    void close(){closed=true;io.shutdownNow();}
}
