package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.Locale;

/** Grants only the selected file, never a remote credential or executable add-on. */
final class ExternalApps {
    private ExternalApps() {}
    static boolean rar(String name) {
        if (name == null) return false;
        String lower=name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".rar") || lower.endsWith(".cbr");
    }
    static String archiveMime(String name) {
        return name!=null && name.toLowerCase(Locale.ROOT).endsWith(".cbr")
                ? "application/x-cbr" : "application/vnd.rar";
    }
    static Uri localUri(Activity a, Uri source) {
        if ("content".equalsIgnoreCase(source.getScheme())) return source;
        if ("file".equalsIgnoreCase(source.getScheme()) && source.getPath()!=null)
            return FileProvider.getUriForFile(a,a.getPackageName()+".files",new File(source.getPath()));
        throw new IllegalArgumentException("Unsupported local URI");
    }
    private static Intent view(Activity a, Uri uri, String type) {
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,type);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(a.getContentResolver(),"FileHub AIO",uri));
        return intent;
    }
    static void openArchive(Activity a, Uri uri, String name) {
        open(a, uri, archiveMime(name), true);
    }
    static boolean openVideo(Activity a, Uri uri) {
        return open(a,uri,"video/*",false);
    }
    private static boolean open(Activity a, Uri uri, String type, boolean archive) {
        try {
            Intent intent=view(a,localUri(a,uri),type);
            if (archive && a.getPackageManager().queryIntentActivities(intent,0).isEmpty())
                intent.setDataAndType(intent.getData(),"application/x-rar-compressed");
            if (!a.getPackageManager().queryIntentActivities(intent,0).isEmpty()) {
                a.startActivity(Intent.createChooser(intent,L10n.t(archive?
                        "외부 압축 앱으로 열기":"외부 플레이어로 재생")));
                return true;
            }
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {}
        new AlertDialog.Builder(a).setTitle(L10n.t("연결 앱 없음"))
                .setMessage(L10n.t(archive?
                        "RAR 또는 CBR 파일을 열 수 있는 외부 앱을 설치하세요.":
                        "이 영상을 재생할 수 있는 외부 플레이어를 설치하세요."))
                .setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("Google Play 열기"),(d,w)->{
                    if(archive) store(a,"com.rarlab.rar");
                    else playerStore(a);
                }).show();
        return false;
    }
    static void playerStore(Activity a) { store(a,"org.videolan.vlc"); }
    private static void store(Activity a,String packageName) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id="+packageName))); }
        catch (ActivityNotFoundException noStore) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id="+packageName))); }
            catch (ActivityNotFoundException ignored) {
                Toast.makeText(a,L10n.t("앱 스토어를 열 수 없습니다."),Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Bounded staging for remote archives only. Never pass authentication to another app. */
    static File stageArchive(Activity a, Models.RemoteProfile profile, Models.Entry entry) throws Exception {
        long max=ArchiveCodec.MAX_INPUT_BYTES;
        if(entry.size>max) throw new IOException(L10n.t("외부 앱용 임시 파일의 크기 제한을 넘었습니다."));
        File root=new File(a.getCacheDir(),"external_archives");
        if(!root.isDirectory()&&!root.mkdirs()) throw new IOException("Cannot create external cache");
        File[] previous=root.listFiles();
        if(previous!=null) for(File folder:previous) {
            if(!folder.isDirectory() || System.currentTimeMillis()-folder.lastModified()<86400000L) continue;
            File[] contents=folder.listFiles();
            if(contents!=null) for(File old:contents) if(old.isFile()) old.delete();
            folder.delete();
        }
        File dir=new File(root,java.util.UUID.randomUUID().toString());
        if(!dir.mkdir()) throw new IOException("Cannot create staging folder");
        String safe=entry.name.replaceAll("[\\\\/:*?\"<>|]","_");
        if(safe.isEmpty()||safe.equals(".")||safe.equals(".."))safe="archive.rar";
        File output=new File(dir,safe);
        try(InputStream input=RemoteProvider.openStream(profile,entry.path,0);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
            byte[] buffer=new byte[65536];long total=0;int n;
            while((n=input.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(n==0)continue;
                if((total+=n)>max)throw new IOException(L10n.t("외부 앱용 임시 파일의 크기 제한을 넘었습니다."));
                out.write(buffer,0,n);
            }
            if(entry.size>0 && total!=entry.size)throw new IOException("Remote file size changed");
            if(total==0)throw new IOException("Empty archive");
            return output;
        }catch(Exception e){output.delete();dir.delete();throw e;}
    }
}
