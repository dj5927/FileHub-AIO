package com.nasmount.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Read-only, URI-granted, bounded-lived external playback of remote files.
 * The URI is an opaque capability; server credentials and paths stay private.
 */
public final class ExternalMediaProvider extends ContentProvider {
    private static final long LIFETIME_MS=4L*60*60*1000;
    private static final int MAX_SESSIONS=16;
    private static final Map<String,Session> sessions=new ConcurrentHashMap<>();
    private static final AtomicInteger descriptors=new AtomicInteger();
    private static final class Session {
        final Models.RemoteProfile profile;
        final String path,name;
        final long size,expires;
        Session(Models.RemoteProfile p,String path,String name,long size) {
            profile=p;this.path=RemoteProvider.relativePath(path);this.name=name;this.size=size;
            expires=android.os.SystemClock.elapsedRealtime()+LIFETIME_MS;
        }
    }
    static synchronized Uri issue(Context c, Models.RemoteProfile p,String path,String name,long size) throws IOException {
        if(p==null || size<=0 || name==null || name.isEmpty())throw new IOException(L10n.t("원격 영상 크기를 확인할 수 없습니다."));
        long now=android.os.SystemClock.elapsedRealtime();
        sessions.entrySet().removeIf(e->e.getValue().expires<=now);
        if(sessions.size()>=MAX_SESSIONS)throw new IOException("Too many external playback sessions");
        String token=UUID.randomUUID().toString();
        sessions.put(token,new Session(p,path,name,size));
        return new Uri.Builder().scheme("content").authority(c.getPackageName()+".externalmedia")
                .appendPath(token).appendPath(name).build();
    }
    private Session session(Uri uri) {
        if(uri==null || !"content".equals(uri.getScheme()) ||
                !Objects.equals(uri.getAuthority(),getContext().getPackageName()+".externalmedia"))
            throw new SecurityException("Invalid playback URI");
        List<String> parts=uri.getPathSegments();
        if(parts.size()!=2)throw new SecurityException("Invalid playback URI");
        Session s=sessions.get(parts.get(0));
        if(s==null || s.expires<=android.os.SystemClock.elapsedRealtime() || !s.name.equals(parts.get(1)))
            throw new SecurityException("Expired playback URI");
        return s;
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){session(uri);return "video/*";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        Session s=session(uri);
        String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
        MatrixCursor cursor=new MatrixCursor(cols);
        Object[] row=new Object[cols.length];
        for(int i=0;i<cols.length;i++){
            if(OpenableColumns.DISPLAY_NAME.equals(cols[i]))row[i]=s.name;
            else if(OpenableColumns.SIZE.equals(cols[i]))row[i]=s.size;
        }
        cursor.addRow(row);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new SecurityException("Read only");
        Session s=session(uri);
        if(descriptors.incrementAndGet()>8){descriptors.decrementAndGet();throw new FileNotFoundException("Too many playback files");}
        HandlerThread worker=new HandlerThread("FileHub-external-stream");
        worker.start();
        Stream callback=new Stream(s,worker);
        try {
            StorageManager manager=getContext().getSystemService(StorageManager.class);
            if(manager==null)throw new IOException("Storage service unavailable");
            return manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    callback,new Handler(worker.getLooper()));
        } catch(Exception error) {
            callback.onRelease();
            FileNotFoundException failure=new FileNotFoundException("External stream unavailable");
            failure.initCause(error);throw failure;
        }
    }
    private static final class Stream extends ProxyFileDescriptorCallback {
        final Session s;
        final HandlerThread worker;
        InputStream input;
        long position=-1;
        boolean released;
        Stream(Session s,HandlerThread worker){this.s=s;this.worker=worker;}
        @Override public long onGetSize(){return s.size;}
        @Override public synchronized int onRead(long offset,int count,byte[] buffer)throws ErrnoException {
            if(released || offset<0 || count<0 || count>buffer.length ||
                    s.expires<=android.os.SystemClock.elapsedRealtime())throw new ErrnoException("read",OsConstants.EIO);
            if(offset>=s.size || count==0)return 0;
            int wanted=(int)Math.min(count,s.size-offset);
            try{
                if(input==null || offset!=position){
                    closeInput();
                    input=RemoteProvider.openStream(s.profile,s.path,offset);position=offset;
                }
                int total=0;
                while(total<wanted){
                    int n=input.read(buffer,total,wanted-total);
                    if(n<=0)throw new EOFException("Remote stream ended");
                    total+=n;position+=n;
                }
                return total;
            }catch(Exception error){closeInput();throw new ErrnoException("read",OsConstants.EIO);}
        }
        private void closeInput(){
            if(input!=null)try{input.close();}catch(IOException ignored){}
            input=null;position=-1;
        }
        @Override public synchronized void onRelease(){
            if(released)return;released=true;
            closeInput();descriptors.decrementAndGet();worker.quitSafely();
        }
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new SecurityException("Read only");}
    @Override public int update(Uri uri,ContentValues values,String where,String[] args){throw new SecurityException("Read only");}
    @Override public int delete(Uri uri,String where,String[] args){throw new SecurityException("Read only");}
}
