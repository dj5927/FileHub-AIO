package com.nasmount.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import java.io.*;
import java.util.UUID;

/** Same safe file source contract for both built-in readers. */
final class ReaderSource {
    static final String KIND="reader_kind",NAME="reader_name",
            PATH="reader_path",URI="reader_uri",PROFILE="reader_profile",
            FOLDER_SCOPE="reader_folder_scope";
    static final long MAX_ARCHIVE=1536L*1024*1024;
    private ReaderSource(){}
    static InputStream open(Activity activity,Intent request)throws Exception{
        String kind=request.getStringExtra(KIND);
        if("local".equals(kind)) {
            String path=request.getStringExtra(PATH);
            if(path==null)throw new IOException("파일 경로가 없습니다.");
            return new BufferedInputStream(new FileInputStream(path));
        }
        if("saf".equals(kind)){
            String raw=request.getStringExtra(URI);
            if(raw==null)throw new IOException("문서 URI가 없습니다.");
            InputStream in=activity.getContentResolver().openInputStream(Uri.parse(raw));
            if(in==null)throw new IOException("문서를 읽을 수 없습니다.");
            return new BufferedInputStream(in);
        }
        if("remote".equals(kind)){
            String id=request.getStringExtra(PROFILE);
            String path=request.getStringExtra(PATH);
            if(id==null||path==null)throw new IOException("원격 파일 정보가 없습니다.");
            for(Models.RemoteProfile p:new SecureStore(activity).activeRemotes())
                if(id.equals(p.id))return new BufferedInputStream(
                        RemoteProvider.openStream(p,path,0));
            throw new IOException("원격 연결을 찾을 수 없습니다.");
        }
        throw new IOException("지원하지 않는 파일 출처입니다.");
    }
    static File stage(Activity activity,Intent request,long limit)throws Exception{
        File folder=new File(activity.getCacheDir(),"book_reader");
        if(!folder.isDirectory()&&!folder.mkdirs())
            throw new IOException("독서 캐시를 만들지 못했습니다.");
        File output=new File(folder,UUID.randomUUID().toString()+".staged");
        try(InputStream source=open(activity,request);
            OutputStream dest=new BufferedOutputStream(new FileOutputStream(output))){
            byte[] block=new byte[65536];int n;long total=0;
            while((n=source.read(block))>=0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(n==0)continue;
                total+=n;
                if(total>limit)throw new IOException("파일 크기가 독서 뷰어 제한을 넘었습니다.");
                dest.write(block,0,n);
            }
            if(total==0)throw new IOException("빈 파일입니다.");
            return output;
        }catch(Exception error){
            output.delete();
            throw error;
        }
    }
    static String bookKey(Intent intent){
        return intent.getStringExtra(KIND)+"|"+
                intent.getStringExtra(PROFILE)+"|"+
                intent.getStringExtra(PATH)+"|"+
                intent.getStringExtra(URI);
    }
}
