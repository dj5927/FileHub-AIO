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
    private static final long MAX_IMAGE_BYTES=100L*1024L*1024L;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private File staged;
    private PhotoCanvas canvas;
    private TextView info;

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
        TextView title=Ui.text(this,
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
        canvas=new PhotoCanvas(this);
        stage.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        info=Ui.text(this,"이미지 읽는 중...",12,0xFFA4B7BD,false);
        info.setGravity(Gravity.CENTER);
        root.addView(info,Ui.lp(this,-1,29));
        load();
    }

    private InputStream fromSource() throws Exception {
        String kind=getIntent().getStringExtra(EXTRA_KIND);
        if("local".equals(kind))
            return new FileInputStream(getIntent().getStringExtra(EXTRA_PATH));
        if("uri".equals(kind))
            return getContentResolver().openInputStream(
                    Uri.parse(getIntent().getStringExtra(EXTRA_URI)));
        if("remote".equals(kind)) {
            String id=getIntent().getStringExtra(EXTRA_PROFILE);
            for(Models.RemoteProfile profile:new SecureStore(this).activeRemotes())
                if(profile.id.equals(id))
                    return RemoteProvider.openStream(profile,
                            RemoteProvider.relativePath(
                                    getIntent().getStringExtra(EXTRA_PATH)),0);
            throw new IOException("원격 이미지 연결이 없습니다.");
        }
        throw new IOException("이미지 위치가 올바르지 않습니다.");
    }

    private void load(){
        worker.execute(()->{
            try {
                File dir=new File(getCacheDir(),"image_viewer");
                if(!dir.exists()&&!dir.mkdirs())
                    throw new IOException("이미지 캐시를 만들 수 없습니다.");
                staged=new File(dir,UUID.randomUUID().toString()+".img");
                try(InputStream input=fromSource();
                    FileOutputStream output=new FileOutputStream(staged)){
                    if(input==null)throw new IOException("이미지를 열 권한이 없습니다.");
                    byte[] buffer=new byte[65536];long total=0;int read;
                    while((read=input.read(buffer))>=0) {
                        if(read==0)continue;
                        total+=read;
                        if(total>MAX_IMAGE_BYTES)
                            throw new IOException("100MB 초과 이미지는 현재 제한을 넘습니다.");
                        output.write(buffer,0,read);
                    }
                }
                if(staged.length()==0)throw new IOException("비어 있는 이미지입니다.");
                Drawable picture=decode(staged);
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    canvas.setImageDrawable(picture);
                    if(picture instanceof Animatable)((Animatable)picture).start();
                    info.setText("두 손가락 확대·축소  ·  한 손가락 이동  ·  두 번 탭하여 원래 크기");
                });
            }catch(Exception e){
                final String error=e.getMessage()==null?"이미지 파일을 읽을 수 없습니다.":e.getMessage();
                runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed())info.setText(error);
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
        private final Matrix matrix=new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private float zoom=1f, panX=0f,panY=0f,lastX,lastY;
        PhotoCanvas(Activity activity){
            super(activity);
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
                    lastX=event.getX();lastY=event.getY();return true;
                case MotionEvent.ACTION_POINTER_DOWN:
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
                case MotionEvent.ACTION_CANCEL:return true;
            }
            return true;
        }
    }
}
