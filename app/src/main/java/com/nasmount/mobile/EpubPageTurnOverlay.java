package com.nasmount.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Draws the outgoing EPUB page over the already prepared next page. The curl
 * uses a curved advancing paper edge, reflected rear face and dynamic shadows
 * so WebView does not need JavaScript or a third-party page-curl dependency.
 */
final class EpubPageTurnOverlay extends View {
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint paper=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crease=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path front=new Path(),back=new Path(),edge=new Path();
    private final RectF viewport=new RectF();
    private Bitmap source,incoming;
    private ValueAnimator animator;
    private int direction=1,style=EpubTurnGeometry.CURL,paperColor=Color.WHITE;
    private float progress,diagonal,grabHeight=.5f;

    EpubPageTurnOverlay(Context context){
        super(context);
        // Consume stray taps while the sheet is curling; otherwise a center
        // tap could resize the book and spoil an in-flight turn.
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
    }
    boolean holding(){return source!=null;}
    void hold(Bitmap bitmap,int direction,int style,int paperColor) {
        clear();
        if(bitmap==null||bitmap.isRecycled())return;
        source=bitmap;
        this.direction=direction<0?-1:1;
        this.style=style;
        this.paperColor=paperColor;
        progress=0;
        diagonal=0;
        grabHeight=.5f;
        setVisibility(VISIBLE);
        invalidate();
    }
    /** Fractional height where the user originally pinched the page edge. */
    void setGrabHeight(float pageY){
        grabHeight=PageCurlPhysics.clamp(pageY);
        invalidate();
    }
    /**
     * A previous-page turn must unfold the *incoming* sheet from the left,
     * not peel the current sheet off in the same direction as "next".
     */
    void setIncoming(Bitmap bitmap){
        if(incoming!=null&&incoming!=bitmap){
            Bitmap previous=incoming;
            postDelayed(()->{if(!previous.isRecycled())previous.recycle();},120);
        }
        incoming=bitmap;
        invalidate();
    }
    /** The corner follows the finger every MOVE, without any timer. */
    void followFinger(float position,float diagonalMovement){
        if(source==null||source.isRecycled())return;
        progress=EpubTurnGeometry.clamp(position);
        diagonal=Math.max(-1f,Math.min(1f,diagonalMovement));
        invalidate();
    }
    float fingerProgress(){return progress;}
    void settle(boolean commit,boolean slow,Runnable completed){
        if(source==null){
            if(completed!=null)completed.run();
            return;
        }
        if(animator!=null){
            animator.removeAllListeners();
            animator.cancel();
            animator=null;
        }
        final float from=progress,fromTilt=diagonal;
        final float to=commit?1f:0f;
        ValueAnimator a=ValueAnimator.ofFloat(0f,1f);
        animator=a;
        long normal=EpubTurnGeometry.duration(style,slow);
        a.setDuration(Math.max(90L,Math.round(normal*Math.abs(to-from))));
        a.setInterpolator(new PathInterpolator(.28f,.02f,.18f,1f));
        a.addUpdateListener(value->{
            float step=(float)value.getAnimatedValue();
            progress=from+(to-from)*step;
            diagonal=fromTilt*(1f-step);
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation){
                if(animator!=animation)return;
                clear();
                if(completed!=null)completed.run();
            }
        });
        a.start();
    }
    void animateTurn(boolean slow,Runnable completed){
        if(source==null){
            if(completed!=null)completed.run();
            return;
        }
        ValueAnimator a=ValueAnimator.ofFloat(0f,1f);
        animator=a;
        a.setDuration(EpubTurnGeometry.duration(style,slow));
        // A quick initial lift followed by a gently settling paper edge
        // feels less mechanical than a perfectly symmetric ease-in/ease-out.
        a.setInterpolator(new PathInterpolator(.24f,.02f,.20f,1f));
        a.addUpdateListener(value->{
            progress=(float)value.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter(){
            @Override public void onAnimationEnd(Animator animation) {
                if(animator!=animation)return;
                clear();
                if(completed!=null)completed.run();
            }
        });
        a.start();
    }
    void clear(){
        if(animator!=null){
            animator.removeAllListeners();
            animator.cancel();
            animator=null;
        }
        if(source!=null){
            Bitmap old=source;
            // Hardware Canvas may still have one queued frame referring to
            // this texture; recycle after RenderThread had time to present it.
            postDelayed(()->{
                if(!old.isRecycled())old.recycle();
            },120);
            source=null;
        }
        if(incoming!=null){
            Bitmap old=incoming;
            postDelayed(()->{
                if(!old.isRecycled())old.recycle();
            },120);
            incoming=null;
        }
        progress=0;
        diagonal=0;
        grabHeight=.5f;
        setVisibility(GONE);
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(source==null||source.isRecycled())return;
        float w=getWidth(),h=getHeight();
        if(w<1||h<1)return;
        viewport.set(0,0,w,h);
        float p=EpubTurnGeometry.clamp(progress);
        if(p<=.0001f){
            drawOld(canvas);
            return;
        }
        if(p>=.9999f)return;
        if(style==EpubTurnGeometry.FADE){
            image.setAlpha(Math.round(255*(1-p)));
            canvas.drawBitmap(source,null,viewport,image);
            image.setAlpha(255);
            return;
        }
        if(style==EpubTurnGeometry.SLIDE){
            canvas.save();
            canvas.translate(-direction*p*w,0);
            drawOld(canvas);
            canvas.restore();
            return;
        }
        drawCurl(canvas,w,h,p);
    }
    private void drawOld(Canvas canvas){
        image.setAlpha(255);
        canvas.drawBitmap(source,null,viewport,image);
    }
    private void drawIncoming(Canvas canvas){
        if(incoming!=null&&!incoming.isRecycled()){
            image.setAlpha(255);
            canvas.drawBitmap(incoming,null,viewport,image);
        }
    }
    private float edgeAt(float width,float p,float y,boolean forward,
                         boolean underside){
        float result=PageCurlPhysics.edgeX(
                width,p,grabHeight,diagonal,y,forward);
        if(underside)result+=(forward?1f:-1f)*PageCurlPhysics.backWidth(
                width,p,grabHeight,diagonal,y);
        return Math.max(0,Math.min(width,result));
    }
    /** Smooth curve through multiple height samples: no fixed bottom-leading
     * corner or straight diagonal line regardless of where the page is held. */
    private void traceEdge(Path path,float w,float h,float p,
                           boolean forward,boolean underside,boolean reverse){
        final int steps=16;
        if(!reverse){
            for(int i=1;i<=steps;i++){
                float center=(i-.5f)/steps;
                float end=(float)i/steps;
                path.quadTo(edgeAt(w,p,center,forward,underside),h*center,
                        edgeAt(w,p,end,forward,underside),h*end);
            }
        }else{
            for(int i=steps-1;i>=0;i--){
                float center=(i+.5f)/steps;
                float end=(float)i/steps;
                path.quadTo(edgeAt(w,p,center,forward,underside),h*center,
                        edgeAt(w,p,end,forward,underside),h*end);
            }
        }
    }
    private void drawCurl(Canvas canvas,float w,float h,float p){
        boolean forward=direction>0;
        boolean unfoldingPrevious=!forward&&incoming!=null&&
                !incoming.isRecycled();
        float top=edgeAt(w,p,0f,forward,false);
        float bottom=edgeAt(w,p,1f,forward,false);
        float backBottom=edgeAt(w,p,1f,forward,true);
        // On previous-page turns the current page is the background and the
        // preceding sheet unfolds INTO the frame from the left.
        if(unfoldingPrevious)drawOld(canvas);
        front.reset();
        if(forward||unfoldingPrevious){
            front.moveTo(0,0);
            front.lineTo(top,0);
        }else{
            front.moveTo(w,0);
            front.lineTo(top,0);
        }
        traceEdge(front,w,h,p,forward,false,false);
        front.lineTo(forward||unfoldingPrevious?0:w,h);
        front.close();
        canvas.save();
        canvas.clipPath(front);
        if(unfoldingPrevious)drawIncoming(canvas);
        else drawOld(canvas);
        canvas.restore();
        edge.reset();
        edge.moveTo(top,0);
        traceEdge(edge,w,h,p,forward,false,false);
        // The contact shadow follows the entire curved fold, not a fixed
        // vertical stripe. Mirror the shadow with the page direction.
        canvas.save();
        canvas.clipOutPath(front);
        shade.setShader(null);
        shade.setStyle(Paint.Style.STROKE);
        shade.setStrokeCap(Paint.Cap.ROUND);
        shade.setStrokeWidth(Math.max(2f,w*.046f));
        shade.setColor(0x17000000);
        canvas.drawPath(edge,shade);
        shade.setStrokeWidth(Math.max(1f,w*.019f));
        shade.setColor(0x24000000);
        canvas.drawPath(edge,shade);
        shade.setStyle(Paint.Style.FILL);
        canvas.restore();
        // Reflected underside is thickest near the actual gripped height.
        if(PageCurlPhysics.backWidth(w,p,grabHeight,diagonal,
                PageCurlPhysics.activeGrab(grabHeight,diagonal))>.8f){
            back.reset();
            back.moveTo(top,0);
            traceEdge(back,w,h,p,forward,false,false);
            back.lineTo(backBottom,h);
            traceEdge(back,w,h,p,forward,true,true);
            back.close();
            canvas.save();
            canvas.clipPath(back);
            // A faint reflected impression helps the paper read as a thin
            // sheet, while the paper tint prevents mirrored text dominating.
            float axis=(top+bottom)/2f;
            canvas.translate(2*axis,0);
            canvas.scale(-1,1);
            if(unfoldingPrevious)drawIncoming(canvas);
            else drawOld(canvas);
            canvas.restore();
            paper.setColor(paperColor);
            paper.setAlpha(222);
            canvas.drawPath(back,paper);
            paper.setAlpha(255);
            float creaseX=edgeAt(w,p,.5f,forward,false);
            float signedBend=edgeAt(w,p,.5f,forward,true)-creaseX;
            if(Math.abs(signedBend)>1f){
                shade.setShader(new LinearGradient(creaseX,0,
                    creaseX+signedBend,0,
                    new int[]{0x4C000000,0x30FFFFFF,0x08000000},
                    new float[]{0f,.45f,1f},Shader.TileMode.CLAMP));
                canvas.drawPath(back,shade);
                shade.setShader(null);
            }
        }
        crease.setStyle(Paint.Style.STROKE);
        crease.setStrokeWidth(Math.max(1f,w*.0012f));
        crease.setColor(0x44808080);
        canvas.drawPath(edge,crease);
        crease.setStyle(Paint.Style.FILL);
    }
}
