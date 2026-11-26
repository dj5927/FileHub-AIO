package com.nasmount.mobile;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline built-in photo viewer with fit, pinch, pan, double tap and GIF. */
public final class ImageViewerActivity extends LocalizedActivity {
    static final String EXTRA_KIND="image_kind";
    static final String EXTRA_NAME="image_name";
    static final String EXTRA_URI="image_uri";
    static final String EXTRA_PATH="image_path";
    static final String EXTRA_PROFILE="image_profile";
    static final String EXTRA_SEQUENCE="image_folder_sequence";
    private static final long MAX_IMAGE_BYTES=100L*1024L*1024L;
    // Cache the current photo and both neighbors. Two IO workers let a remote
    // photo load without discarding the image already visible to the user.
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private static final class LoadedPhoto {
        final File file;
        final Drawable drawable;
        LoadedPhoto(File file, Drawable drawable) {
            this.file=file;
            this.drawable=drawable;
        }
    }
    private final LinkedHashMap<Integer,LoadedPhoto> loaded=
            new LinkedHashMap<>(8,0.75f,true);
    private final Set<Integer> loading=new HashSet<>();
    private FrameLayout stage;
    private PhotoCanvas canvas;
    private ImageView previousCanvas, nextCanvas;
    private TextView info;
    private TextView title;
    private ImageFolderSequence.Snapshot sequence;
    private int sequenceIndex=-1;
    private int previewPrevious=-2,previewNext=-2;
    private int pendingIndex=-1,pendingDirection=0;
    private ValueAnimator slideAnimator;
    private float slideX;
    private boolean animating, fingerDown, currentReady, destroyed;
    private float fingerDx;

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
        setContentView(EditionMonetization.content(this,root));
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
        LinearLayout imageBar=Ui.horizontal(this);
        imageBar.addView(title,Ui.lp(this,-1,-1));
        root.addView(imageBar,Ui.lp(this,-1,44));
        stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        previousCanvas=new ImageView(this);
        previousCanvas.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(previousCanvas,new FrameLayout.LayoutParams(-1,-1));
        nextCanvas=new ImageView(this);
        nextCanvas.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(nextCanvas,new FrameLayout.LayoutParams(-1,-1));
        canvas=new PhotoCanvas(this,new PhotoCanvas.SwipeListener(){
            @Override public void onStart(){fingerDown=true;pendingIndex=-1;}
            @Override public void onDrag(float dx){dragPhoto(dx);}
            @Override public void onRelease(int direction){releasePhoto(direction);}
            @Override public void onCancel(){fingerDown=false;slideBack();}
        });
        stage.addView(canvas,new FrameLayout.LayoutParams(-1,-1));
        stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->
                applyPageOffsets(slideX));
        info=Ui.text(this,L10n.t("이미지 읽는 중..."),12,0xFFA4B7BD,false);
        info.setGravity(Gravity.CENTER);
        root.addView(info,Ui.lp(this,-1,29));
        updateTitle();
        installViewerChrome(imageBar,()->ViewerUiPolicy.state("image",
                currentPhoto().name+(sequence!=null&&sequence.photos.size()>1?
                        "  ("+(sequenceIndex+1)+"/"+sequence.photos.size()+")":""),
                0xFF080C10,Color.WHITE,-1,false,"back",
                currentReady&&!animating&&!fingerDown&&neighbor(-1)>=0?"previous":null,
                currentReady&&!animating&&!fingerDown&&neighbor(1)>=0?"next":null),
                ()->!destroyed,(action,value)->{
                    if("back".equals(action))finish();
                    else if("previous".equals(action))releasePhoto(-1);
                    else if("next".equals(action))releasePhoto(1);
                });
        requestPhoto(currentIndex());
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

    private int currentIndex() {
        return sequence==null ? 0 : sequenceIndex;
    }

    private ImageFolderSequence.Photo photoAt(int index) {
        return sequence==null ? currentPhoto() : sequence.photos.get(index);
    }

    private int neighbor(int direction) {
        return sequence==null ? -1 :
                sequence.neighbor(sequenceIndex,direction);
    }

    private void updateTitle() {
        ImageFolderSequence.Photo photo=currentPhoto();
        title.setText("‹  "+photo.name+
                (sequence!=null && sequence.photos.size()>1 ?
                        "  ("+(sequenceIndex+1)+"/"+sequence.photos.size()+")":""));
    }

    private void readyText() {
        info.setText(sequence!=null && sequence.photos.size()>1 ?
                "좌우로 밀어 사진 넘기기  ·  두 손가락 확대  ·  두 번 탭" :
                "두 손가락 확대·축소  ·  한 손가락 이동  ·  두 번 탭하여 원래 크기");
    }

    private static void stopImage(ImageView view) {
        Drawable d=view.getDrawable();
        if(d instanceof Animatable)((Animatable)d).stop();
    }

    private Drawable previewCopy(Drawable original) {
        Drawable.ConstantState state=original.getConstantState();
        return state==null?original:state.newDrawable(getResources()).mutate();
    }

    private void showPreview(ImageView view,int index,boolean previous) {
        int shown=previous?previewPrevious:previewNext;
        if(index==shown)return;
        stopImage(view);
        LoadedPhoto photo=index<0?null:loaded.get(index);
        view.setImageDrawable(photo==null?null:previewCopy(photo.drawable));
        view.setVisibility(photo==null?View.INVISIBLE:View.VISIBLE);
        if(previous)previewPrevious=photo==null?-2:index;
        else previewNext=photo==null?-2:index;
    }

    private void refreshPreviews() {
        showPreview(previousCanvas,neighbor(-1),true);
        showPreview(nextCanvas,neighbor(1),false);
        applyPageOffsets(slideX);
    }

    private void applyPageOffsets(float x) {
        if(stage==null||canvas==null)return;
        slideX=x;
        float width=stage.getWidth();
        canvas.setTranslationX(x);
        previousCanvas.setTranslationX(
                ImagePagerMotionPolicy.neighborOffset(x,width,-1));
        nextCanvas.setTranslationX(
                ImagePagerMotionPolicy.neighborOffset(x,width,1));
    }

    private void dragPhoto(float dx) {
        fingerDx=dx;
        if(animating||!currentReady)return;
        int dir=dx<0f?1:-1;
        int target=neighbor(dir);
        boolean ready=target>=0 && loaded.containsKey(target);
        applyPageOffsets(ImagePagerMotionPolicy.offset(
                dx,stage.getWidth(),ready));
    }

    private void settle(float end,Runnable finished) {
        if(slideAnimator!=null)slideAnimator.cancel();
        if(stage.getWidth()<=0) {
            applyPageOffsets(end);
            finished.run();
            return;
        }
        animating=true;
        final ValueAnimator animation=ValueAnimator.ofFloat(slideX,end);
        slideAnimator=animation;
        animation.setDuration(ImagePagerMotionPolicy.durationMs(
                slideX,end,stage.getWidth()));
        animation.setInterpolator(new DecelerateInterpolator(1.35f));
        animation.addUpdateListener(value->applyPageOffsets(
                (Float)value.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter(){
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a){cancelled=true;}
            @Override public void onAnimationEnd(Animator a){
                if(slideAnimator!=animation)return;
                slideAnimator=null;
                animating=false;
                if(!cancelled)finished.run();
                tryPendingPhoto();
            }
        });
        animation.start();
    }

    private void slideBack() {
        if(animating)return;
        if(Math.abs(slideX)<0.5f) {
            applyPageOffsets(0);
            tryPendingPhoto();
            return;
        }
        settle(0,()->applyPageOffsets(0));
    }

    private void releasePhoto(int direction) {
        fingerDown=false;
        fingerDx=0;
        if(animating||!currentReady)return;
        if(direction==0) {
            slideBack();
            return;
        }
        int target=neighbor(direction);
        if(target<0) {
            info.setText(direction>0?"마지막 사진입니다.":"첫 번째 사진입니다.");
            slideBack();
            return;
        }
        if(!loaded.containsKey(target)) {
            pendingIndex=target;
            pendingDirection=direction;
            info.setText(L10n.t("다음 사진 준비 중…"));
            requestPhoto(target);
            slideBack();
            return;
        }
        slideTo(target,direction);
    }

    private void slideTo(int target,int direction) {
        if(animating||fingerDown||!loaded.containsKey(target))return;
        if(target!=neighbor(direction))return;
        pendingIndex=-1;
        refreshPreviews();
        // Current and next photo move on ONE continuous filmstrip. The next
        // bitmap is already at x +/- width before the first animation frame.
        float end=direction>0?-stage.getWidth():stage.getWidth();
        settle(end,()->{
            stopImage(canvas);
            sequenceIndex=target;
            LoadedPhoto photo=loaded.get(target);
            canvas.setImageDrawable(photo.drawable);
            if(photo.drawable instanceof Animatable)
                ((Animatable)photo.drawable).start();
            currentReady=true;
            applyPageOffsets(0);
            updateTitle();
            readyText();
            refreshPreviews();
            prefetchNeighbors();
            trimLoadedPhotos();
        });
    }

    private void tryPendingPhoto() {
        if(destroyed||animating||fingerDown||pendingIndex<0 ||
                !loaded.containsKey(pendingIndex))return;
        slideTo(pendingIndex,pendingDirection);
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

    private void prefetchNeighbors() {
        int before=neighbor(-1),after=neighbor(1);
        if(before>=0)requestPhoto(before);
        if(after>=0)requestPhoto(after);
    }

    private void trimLoadedPhotos() {
        // Three full-resolution images are enough for the interactive
        // strip. A fourth may temporarily exist during a page transition.
        while(loaded.size()>3) {
            int current=currentIndex(),before=neighbor(-1),after=neighbor(1);
            boolean removed=false;
            Iterator<Map.Entry<Integer,LoadedPhoto>> it=
                    loaded.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<Integer,LoadedPhoto> entry=it.next();
                int position=entry.getKey();
                if(position==current||position==before||
                        position==after||position==pendingIndex)continue;
                entry.getValue().file.delete();
                it.remove();
                removed=true;
                break;
            }
            if(!removed)break;
        }
    }

    private void requestPhoto(int index){
        if(index<0||destroyed||loaded.containsKey(index)||
                !loading.add(index))return;
        final ImageFolderSequence.Photo photo=photoAt(index);
        worker.execute(()->{
            File pending=null;
            try {
                File dir=new File(getCacheDir(),"image_viewer");
                if(!dir.exists()&&!dir.mkdirs())
                    throw new IOException("이미지 캐시를 만들 수 없습니다.");
                pending=new File(dir,UUID.randomUUID().toString()+".img");
                try(InputStream input=fromSource(photo);
                    FileOutputStream output=new FileOutputStream(pending)){
                    if(input==null)throw new IOException("이미지를 열 권한이 없습니다.");
                    byte[] buffer=new byte[65536];long total=0;int read;
                    while((read=input.read(buffer))>=0) {
                        if(Thread.currentThread().isInterrupted())
                            throw new IOException("이미지 읽기가 중단됐습니다.");
                        if(read==0)continue;
                        total+=read;
                        if(total>MAX_IMAGE_BYTES)
                            throw new IOException("100MB 초과 이미지는 현재 제한을 넘습니다.");
                        output.write(buffer,0,read);
                    }
                }
                if(pending.length()==0)throw new IOException("비어 있는 이미지입니다.");
                Drawable picture=decode(pending);
                File completed=pending;
                pending=null;
                runOnUiThread(()->{
                    loading.remove(index);
                    if(destroyed||isFinishing()||isDestroyed()){
                        completed.delete();return;
                    }
                    loaded.put(index,new LoadedPhoto(completed,picture));
                    if(index==currentIndex()&&!currentReady) {
                        canvas.setImageDrawable(picture);
                        if(picture instanceof Animatable)
                            ((Animatable)picture).start();
                        currentReady=true;
                        readyText();
                        prefetchNeighbors();
                    }
                    refreshPreviews();
                    if(fingerDown&&fingerDx!=0)dragPhoto(fingerDx);
                    tryPendingPhoto();
                    trimLoadedPhotos();
                });
            }catch(Exception e){
                if(pending!=null)pending.delete();
                final String error=e.getMessage()==null?"이미지 파일을 읽을 수 없습니다.":e.getMessage();
                runOnUiThread(()->{
                    loading.remove(index);
                    if(destroyed||isFinishing()||isDestroyed())return;
                    if(index==pendingIndex) {
                        pendingIndex=-1;
                        info.setText(L10n.t("다음 사진을 불러오지 못했습니다: ")+error);
                        slideBack();
                    }else if(index==currentIndex()&&!currentReady)
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
                        // Keep three images resident on a Fold without
                        // decoding three 64MB 4096x4096 bitmaps at once.
                        if(max>3072)decoder.setTargetSampleSize(
                                Math.max(1,(int)Math.ceil(max/3072.0)));
                    });
        }
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(image.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)
            throw new IOException("지원되지 않는 이미지 형식입니다.");
        int sample=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/sample>3072)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=sample;
        Bitmap bitmap=BitmapFactory.decodeFile(image.getAbsolutePath(),options);
        if(bitmap==null)throw new IOException("이미지를 해독하지 못했습니다.");
        return new android.graphics.drawable.BitmapDrawable(getResources(),bitmap);
    }

    @Override protected void onDestroy(){
        destroyed=true;
        if(slideAnimator!=null)slideAnimator.cancel();
        worker.shutdownNow();
        if(canvas!=null)stopImage(canvas);
        if(previousCanvas!=null)stopImage(previousCanvas);
        if(nextCanvas!=null)stopImage(nextCanvas);
        for(LoadedPhoto photo:loaded.values())photo.file.delete();
        loaded.clear();
        super.onDestroy();
    }

    private static final class PhotoCanvas extends ImageView {
        interface SwipeListener {
            void onStart();
            void onDrag(float dx);
            void onRelease(int direction);
            void onCancel();
        }
        private final Matrix matrix=new Matrix();
        private final ScaleGestureDetector scaleDetector;
        private final GestureDetector tapDetector;
        private final SwipeListener swipeListener;
        private float zoom=1f, panX=0f,panY=0f,lastX,lastY;
        private float touchStartX,touchStartY;
        private boolean multiTouch,pageDragging;
        private VelocityTracker velocity;
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
            // The outgoing and incoming canvases must share the exact same
            // fit transform on the *same* animation frame. Posting only to
            // the next frame momentarily stretches the new picture with
            // the old one's matrix (a visible snap on aspect-ratio changes).
            if(getWidth()>0&&getHeight()>0)applyMatrix();
            else post(this::applyMatrix);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
            super.onSizeChanged(w,h,oldW,oldH);
            post(this::applyMatrix);
        }
        private void trackRawVelocity(MotionEvent event) {
            if(velocity==null)return;
            // The photo itself is translated during the gesture. Android's
            // event.getX() is LOCAL TO THAT MOVING VIEW and would oscillate
            // (e.g. finger -100px -> photo -100px -> next local dx almost 0).
            // Track stable physical screen coordinates for drag and fling.
            MotionEvent raw=MotionEvent.obtain(event);
            raw.offsetLocation(event.getRawX()-event.getX(),
                    event.getRawY()-event.getY());
            velocity.addMovement(raw);
            raw.recycle();
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
                    touchStartX=event.getRawX();touchStartY=event.getRawY();
                    multiTouch=false;pageDragging=false;
                    if(velocity!=null)velocity.recycle();
                    velocity=VelocityTracker.obtain();
                    trackRawVelocity(event);
                    swipeListener.onStart();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_POINTER_UP:
                    multiTouch=true;
                    if(pageDragging)swipeListener.onCancel();
                    pageDragging=false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    trackRawVelocity(event);
                    if(event.getPointerCount()==1&&!scaleDetector.isInProgress()){
                        if(zoom>1.05f) {
                            panX+=event.getX()-lastX;
                            panY+=event.getY()-lastY;
                            applyMatrix();
                        }else if(!multiTouch) {
                            float dx=event.getRawX()-touchStartX;
                            float dy=event.getRawY()-touchStartY;
                            if(!pageDragging&&ImagePagerMotionPolicy.horizontal(
                                    dx,dy,getResources().getDisplayMetrics().density))
                                pageDragging=true;
                            if(pageDragging)swipeListener.onDrag(dx);
                        }
                    }
                    lastX=event.getX();lastY=event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float velocityX=0;
                    if(velocity!=null){
                        trackRawVelocity(event);
                        velocity.computeCurrentVelocity(1000);
                        velocityX=velocity.getXVelocity();
                        velocity.recycle();velocity=null;
                    }
                    int direction=pageDragging?
                            ImagePagerMotionPolicy.releaseDirection(
                                    event.getRawX()-touchStartX,
                                    event.getRawY()-touchStartY,velocityX,
                                    getWidth(),getResources().getDisplayMetrics().density,
                                    zoom,multiTouch||scaleDetector.isInProgress()):0;
                    swipeListener.onRelease(direction);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if(velocity!=null){velocity.recycle();velocity=null;}
                    swipeListener.onCancel();
                    return true;
            }
            return true;
        }
    }
}
