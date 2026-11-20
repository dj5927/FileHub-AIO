package com.nasmount.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline built-in photo viewer with fit, pinch, pan, double tap and GIF. */
public final class ImageViewerActivity extends Activity {
    static final String EXTRA_KIND="image_kind";
    static final String EXTRA_NAME="image_name";
    static final String EXTRA_URI="image_uri";
    static final String EXTRA_PATH="image_path";
    static final String EXTRA_PROFILE="image_profile";
    static final String EXTRA_SEQUENCE="image_folder_sequence";
    private static final long MAX_IMAGE_BYTES=100L*1024L*1024L;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private File staged;
    private PhotoCanvas canvas;
    private TextView info;
    private TextView title;
    private ImageFolderSequence.Snapshot sequence;
    private int sequenceIndex=-1;
    private volatile int loadGeneration;

    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root=Ui.vertical(this);
        root.setBackgroundColor(0xFF080C10);
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|
                    WindowInsetsCompat.Type.navigationBars()|
                    WindowInsetsCompat.Type.displayCutout());
            root.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(root);
        String token=getIntent().getStringExtra(EXTRA_SEQUENCE);
        sequence=ImageFolderSequence.resolve(token);
        if(sequence!=null) {
            String kind=getIntent().getStringExtra(EXTRA_KIND);
            String profile=getIntent().getStringExtra(EXTRA_PROFILE);
            String locator="uri".equals(kind)?
                    getIntent().getStringExtra(EXTRA_URI):
                    getIntent().getStringExtra(EXTRA_PATH);
            if(!sequence.kind.equals(kind) ||
                    !sequence.profileId.equals(profile==null?"":profile) ||
                    (sequenceIndex=sequence.indexOf(locator))<0) {
                sequence=null;
                sequenceIndex=-1;
            }
        }
        title=Ui.text(this,
                "‹  "+getIntent().getStringExtra(EXTRA_NAME),
                16,Color.WHITE,true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setPadding(Ui.dp(this,13),0,Ui.dp(this,13),0);
        title.setOnClickListener(v->finish());
        root.addView(title,Ui.lp(this,-1,44));
        FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        canvas=new PhotoCanvas(this,this::swipePhoto);
        stage.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        info=Ui.text(this,"이미지 읽는 중...",12,0xFFA4B7BD,false);
        info.setGravity(Gravity.CENTER);
        root.addView(info,Ui.lp(this,-1,29));
        load();
    }

    private ImageFolderSequence.Photo currentPhoto() {
        if(sequence!=null && sequenceIndex>=0)
            return sequence.photos.get(sequenceIndex);
        String kind=getIntent().getStringExtra(EXTRA_KIND);
        return new ImageFolderSequence.Photo(
                getIntent().getStringExtra(EXTRA_NAME),
                "uri".equals(kind)?getIntent().getStringExtra(EXTRA_URI):
                        getIntent().getStringExtra(EXTRA_PATH));
    }

    private void swipePhoto(int direction) {
        if(sequence==null || sequenceIndex<0) return;
        int next=sequence.neighbor(sequenceIndex,direction);
        if(next<0) {
            info.setText(direction>0?"마지막 사진입니다.":"첫 번째 사진입니다.");
            return;
        }
        sequenceIndex=next;
        load();
    }

    private InputStream fromSource(ImageFolderSequence.Photo photo) throws Exception {
        String kind=getIntent().getStringExtra(EXTRA_KIND);
        if("local".equals(kind))
            return new FileInputStream(photo.locator);
        if("uri".equals(kind))
            return getContentResolver().openInputStream(
                    Uri.parse(photo.locator));
        if("remote".equals(kind)) {
            String id=getIntent().getStringExtra(EXTRA_PROFILE);
            for(Models.RemoteProfile profile:new SecureStore(this).activeRemotes())
                if(profile.id.equals(id))
                    return RemoteProvider.openStream(profile,
                            RemoteProvider.relativePath(photo.locator),0);
            throw new IOException("원격 이미지 연결이 없습니다.");
        }
        throw new IOException("이미지 위치가 올바르지 않습니다.");
    }

    private void load(){
        final ImageFolderSequence.Photo photo=currentPhoto();
        final int request=++loadGeneration;
        title.setText("‹  "+photo.name+
                (sequence!=null && sequence.photos.size()>1 ?
                        "  ("+(sequenceIndex+1)+"/"+sequence.photos.size()+")":""));
        info.setText("이미지 읽는 중...");
        Drawable oldPicture=canvas.getDrawable();
        if(oldPicture instanceof Animatable)((Animatable)oldPicture).stop();
        canvas.setImageDrawable(null);
        worker.execute(()->{
            File pending=null;
            try {
                if(request!=loadGeneration)return;
                File dir=new File(getCacheDir(),"image_viewer");
                if(!dir.exists()&&!dir.mkdirs())
                    throw new IOException("이미지 캐시를 만들 수 없습니다.");
                pending=new File(dir,UUID.randomUUID().toString()+".img");
                try(InputStream input=fromSource(photo);
                    FileOutputStream output=new FileOutputStream(pending)){
                    if(input==null)throw new IOException("이미지를 열 권한이 없습니다.");
                    byte[] buffer=new byte[65536];long total=0;int read;
                    while((read=input.read(buffer))>=0) {
                        if(request!=loadGeneration ||
                                Thread.currentThread().isInterrupted())
                            throw new IOException("이미지 전환으로 다운로드가 취소됐습니다.");
                        if(read==0)continue;
                        total+=read;
                        if(total>MAX_IMAGE_BYTES)
                            throw new IOException("100MB 초과 이미지는 현재 제한을 넘습니다.");
                        output.write(buffer,0,read);
                    }
                }
                if(pending.length()==0)throw new IOException("비어 있는 이미지입니다.");
                if(request!=loadGeneration){pending.delete();return;}
                Drawable picture=decode(pending);
                File completed=pending;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||request!=loadGeneration){
                        completed.delete();return;
                    }
                    File previous=staged;
                    staged=completed;
                    canvas.setImageDrawable(picture);
                    if(picture instanceof Animatable)((Animatable)picture).start();
                    if(previous!=null && !previous.equals(completed))
                        previous.delete();
                    info.setText(sequence!=null && sequence.photos.size()>1 ?
                            "좌우 스와이프: 이전·다음 사진  ·  두 손가락 확대  ·  두 번 탭" :
                            "두 손가락 확대·축소  ·  한 손가락 이동  ·  두 번 탭하여 원래 크기");
                });
            }catch(Exception e){
                if(pending!=null)pending.delete();
                final String error=e.getMessage()==null?"이미지 파일을 읽을 수 없습니다.":e.getMessage();
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed()&&request==loadGeneration)
                        info.setText(error);
                });
            }
        });
    }

    private Drawable decode(File image)throws Exception {
        if(Build.VERSION.SDK_INT>=28){
            return ImageDecoder.decodeDrawable(ImageDecoder.createSource(image),
                    (decoder,header,source)->{
                        int max=Math.max(header.getSize().getWidth(),
                                header.getSize().getHeight());
                        if(max>4096)decoder.setTargetSampleSize(
                                Math.max(1,(int)Math.ceil(max/4096.0)));
                    });
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(image.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)
            throw new IOException("지원되지 않는 이미지 형식입니다.");
        int sample=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/sample>4096)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=sample;
        Bitmap bitmap=BitmapFactory.decodeFile(image.getAbsolutePath(),options);
        if(bitmap==null)throw new IOException("이미지를 해독하지 못했습니다.");
        return new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
    }

    @Override protected void onDestroy(){
        worker.shutdownNow();
        if(staged!=null)staged.delete();
        super.onDestroy();
    }

    private static final class PhotoCanvas extends ImageView {
        interface SwipeListener { void onSwipe(int direction); }
        private final Matrix matrix=new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private final SwipeListener swipeListener;
        private float zoom=1f, panX=0f,panY=0f,lastX,lastY;
        private float touchStartX,touchStartY;
        private boolean multiTouch;
        PhotoCanvas(Activity activity, SwipeListener swipeListener){
            super(activity);
            this.swipeListener=swipeListener;
            setScaleType(ScaleType.MATRIX);
            scaleDetector=new ScaleGestureDetector(activity,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                        @Override public boolean onScale(ScaleGestureDetector detector){
                            zoom=Math.max(1f,Math.min(6f,zoom*detector.getScaleFactor()));
                            applyMatrix();return true;
                        }
                    });
            tapDetector=new GestureDetector(activity,
                    new GestureDetector.SimpleOnGestureListener(){
                        @Override public boolean onDown(MotionEvent e){return true;}
                        @Override public boolean onDoubleTap(MotionEvent e){
                            zoom=zoom>1.1f?1f:2.5f;
                            panX=0;panY=0;applyMatrix();return true;
                        }
                    });
        }
        @Override public void setImageDrawable(Drawable image){
            super.setImageDrawable(image);
            zoom=1;panX=0;panY=0;
            post(this::applyMatrix);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
            super.onSizeChanged(w,h,oldW,oldH);
            post(this::applyMatrix);
        }
        private void applyMatrix(){
            Drawable drawable=getDrawable();
            if(drawable==null||getWidth()<1||getHeight()<1)return;
            float dw=Math.max(1,drawable.getIntrinsicWidth());
            float dh=Math.max(1,drawable.getIntrinsicHeight());
            float scale=Math.min(getWidth()/dw,getHeight()/dh)*zoom;
            float displayW=dw*scale,displayH=dh*scale;
            float freeX=Math.max(0,(displayW-getWidth())/2f);
            float freeY=Math.max(0,(displayH-getHeight())/2f);
            panX=Math.min(freeX,Math.max(-freeX,panX));
            panY=Math.min(freeY,Math.max(-freeY,panY));
            matrix.reset();
            matrix.setScale(scale,scale);
            matrix.postTranslate((getWidth()-displayW)/2f+panX,
                    (getHeight()-displayH)/2f+panY);
            setImageMatrix(matrix);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            scaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    lastX=event.getX();lastY=event.getY();
                    touchStartX=lastX;touchStartY=lastY;
                    multiTouch=false;return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    multiTouch=true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(event.getPointerCount()==1&&!scaleDetector.isInProgress()&&zoom>1f){
                        panX+=event.getX()-lastX;
                        panY+=event.getY()-lastY;
                        applyMatrix();
                    }
                    lastX=event.getX();lastY=event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    int direction=ImageSwipePolicy.direction(
                            event.getX()-touchStartX,
                            event.getY()-touchStartY,
                            getWidth(),getResources().getDisplayMetrics().density,
                            zoom,multiTouch||scaleDetector.isInProgress());
                    if(direction!=0)swipeListener.onSwipe(direction);
                    return true;
                case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        }
    }
}
