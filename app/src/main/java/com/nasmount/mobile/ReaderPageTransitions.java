package com.nasmount.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Shared, bounded-memory page transitions for image, TextView and WebView
 * readers. Captures outgoing content before any page mutation, then draws
 * the same 2.5D paper curl over the prepared incoming view.
 */
final class ReaderPageTransitions {
    private final Activity activity;
    private final View page;
    private final EpubPageTurnOverlay overlay;
    private int style=EpubTurnGeometry.CURL;
    private boolean slow,busy;
    private int paper=0xFFF8F1E4;
    private int token;
    private final RectF pageBounds=new RectF();
    private boolean fingerMode,fingerDown,fingerReleased,fingerCommit,
            fingerReady,fingerSettling;
    private int fingerDirection;
    private int automaticDirection;
    private float fingerStartX,fingerStartY,fingerX,fingerY;
    private Runnable undoPreview,commitPreview;
    ReaderPageTransitions(Activity activity,FrameLayout stage,View page){
        this.activity=activity;
        this.page=page;
        overlay=new EpubPageTurnOverlay(activity);
        stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        setBounds(null);
    }
    void configure(int effect,boolean slowTurn,int paperColor){
        style=Math.max(0,Math.min(3,effect));
        slow=slowTurn;
        paper=paperColor;
    }
    boolean busy(){return busy;}
    /** Actual page rectangle, rather than the entire black reader stage. */
    void setBounds(RectF localBounds){
        if(busy)return;
        RectF full=new RectF(0,0,page.getWidth(),page.getHeight());
        RectF content=localBounds==null?full:new RectF(localBounds);
        if(!content.intersect(full)||content.width()<2||content.height()<2)
            content.set(full);
        if(content.width()<1||content.height()<1)return;
        pageBounds.set(content);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(
                Math.max(1,Math.round(content.width())),
                Math.max(1,Math.round(content.height())));
        lp.leftMargin=Math.round(content.left);
        lp.topMargin=Math.round(content.top);
        overlay.setLayoutParams(lp);
    }
    float currentWidth(){return pageBounds.width()>0?pageBounds.width():page.getWidth();}
    boolean fingerActive(){return busy&&fingerMode;}
    boolean previewWanted(){return busy&&fingerMode&&
            (fingerDown||(fingerReleased&&fingerCommit));}

    /**
     * Begin an interactive sheet from the last visible page. The caller
     * prepares the NEXT page without persisting reading history; commit is
     * performed only if the finger crosses the release threshold.
     */
    boolean startFinger(int direction,float startX,float startY,
                        Runnable showPreview,Runnable revert,Runnable commit){
        if(busy||style!=EpubTurnGeometry.CURL||
                page.getWidth()<1||page.getHeight()<1)return false;
        busy=true;
        fingerMode=true;
        fingerDown=true;fingerReleased=false;fingerCommit=false;
        fingerReady=false;fingerSettling=false;
        fingerDirection=direction>=0?1:-1;
        fingerStartX=fingerX=startX;
        fingerStartY=fingerY=startY;
        undoPreview=revert;
        commitPreview=commit;
        final int generation=++token;
        RectF captureBounds=new RectF(pageBounds);
        if(captureBounds.isEmpty())
            captureBounds.set(0,0,page.getWidth(),page.getHeight());
        EpubPageCapture.capture(activity,page,captureBounds,snapshot->{
            if(generation!=token||activity.isFinishing()||activity.isDestroyed()){
                if(snapshot!=null&&!snapshot.isRecycled())snapshot.recycle();
                return;
            }
            if(snapshot!=null) {
                overlay.hold(snapshot,fingerDirection,style,paper);
                overlay.setGrabHeight(PageCurlPhysics.clamp(
                        (fingerStartY-captureBounds.top)/
                        Math.max(1f,captureBounds.height())));
                updateFinger(fingerX,fingerY);
            }
            // Decompression/pagination is allowed to be asynchronous.
            showPreview.run();
        });
        return true;
    }
    void updateFinger(float x,float y){
        if(!fingerActive()||!fingerDown)return;
        fingerX=x;fingerY=y;
        float fraction=ReaderDragPhysics.progress(
                fingerStartX,x,currentWidth(),fingerDirection);
        float tilt=ReaderDragPhysics.diagonal(
                fingerStartY,y,Math.max(1,pageBounds.height()));
        overlay.followFinger(fraction,tilt);
    }
    void releaseFinger(boolean allowFastFlick){
        if(!fingerActive()||!fingerDown)return;
        fingerDown=false;
        fingerReleased=true;
        float fraction=ReaderDragPhysics.progress(
                fingerStartX,fingerX,currentWidth(),fingerDirection);
        fingerCommit=ReaderDragPhysics.shouldCommit(fraction)||
                (allowFastFlick&&fraction>.16f);
        if(!fingerCommit){
            // Reject an outstanding incoming-page PixelCopy result. An async
            // callback must never replace a page that is already unfolding
            // back toward its original position.
            ++token;
            // Restoring the underlying old page BEFORE unfolding prevents
            // an accidental flash of the rejected next page.
            if(undoPreview!=null)undoPreview.run();
            overlay.settle(false,slow,()->finishFinger(false));
            if(!overlay.holding())finishFinger(false);
        }else if(fingerReady)finishRelease();
    }
    private void finishRelease(){
        if(!fingerActive()||!fingerReleased||!fingerCommit||
                fingerSettling)return;
        fingerSettling=true;
        if(commitPreview!=null)commitPreview.run();
        overlay.settle(true,slow,()->finishFinger(true));
        if(!overlay.holding())finishFinger(true);
    }
    private void finishFinger(boolean committed){
        if(!fingerMode)return;
        ++token;
        fingerMode=false;
        fingerDown=false;
        busy=false;
        undoPreview=commitPreview=null;
        overlay.clear();
    }
    /**
     * Start a navigation initiated by touch/button. asyncReady=true means
     * next.run() will decode/paginate off the UI thread and must call ready()
     * once the incoming page has actually been drawn.
     */
    boolean begin(int direction,Runnable next,boolean asyncReady){
        if(busy)return false;
        if(direction==0||style==EpubTurnGeometry.NONE||
                page.getWidth()<1||page.getHeight()<1){
            next.run();
            return true;
        }
        busy=true;
        final int generation=++token;
        automaticDirection=direction>=0?1:-1;
        EpubPageCapture.capture(activity,page,
                pageBounds.isEmpty()?null:new RectF(pageBounds),shot->{
            if(generation!=token||activity.isFinishing()||activity.isDestroyed()){
                if(shot!=null&&!shot.isRecycled())shot.recycle();
                return;
            }
            if(shot==null){
                busy=false;
                next.run();
                return;
            }
            overlay.hold(shot,direction,style,paper);
            overlay.setGrabHeight(.5f);
            try{
                next.run();
                if(!asyncReady)page.postDelayed(this::ready,48);
            }catch(RuntimeException failure){
                cancel();
                throw failure;
            }
        });
        return true;
    }
    void ready(){
        if(!busy)return;
        if(fingerMode){
            // The previous-page image/text is already rendered in the view
            // beneath the curl. Capturing the Window here includes our own
            // overlay (OLD page), then the outgoing bitmap is incorrectly
            // used as the incoming sheet and text jumps after animation.
            fingerReady=true;
            if(fingerReleased&&fingerCommit)finishRelease();
            return;
        }
        if(!overlay.holding()){
            busy=false;
            return;
        }
        // Same for backwards button/tap turns. Let the *actual* incoming
        // TextView or decoded comic bitmap gradually appear behind the fold.
        // Never capture the composited Window while our overlay is visible.
        animateReady();
    }
    private void animateReady(){
        if(!busy||!overlay.holding())return;
        // Let TextView/Bitmap redraw after the data changes. Using the same
        // render delay for all reader types avoids text/image flash.
        page.postDelayed(()->{
            if(!busy||!overlay.holding())return;
            overlay.animateTurn(slow,()->busy=false);
        },44);
    }
    void cancel(){
        ++token;
        if(fingerMode&&undoPreview!=null)undoPreview.run();
        fingerMode=false;fingerDown=false;
        fingerReleased=false;fingerReady=false;
        fingerSettling=false;
        undoPreview=commitPreview=null;
        busy=false;
        overlay.clear();
    }
}
