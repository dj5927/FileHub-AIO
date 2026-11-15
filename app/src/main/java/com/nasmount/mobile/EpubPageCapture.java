package com.nasmount.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Small temporary bitmap of the visible page. PixelCopy captures the
 * hardware-rendered WebView correctly on Android 8+, while draw(Canvas)
 * provides a safe fallback on devices where Window capture is unavailable.
 */
final class EpubPageCapture {
    private EpubPageCapture(){}
    static int targetWidth(int width,int height){
        if(width<=0||height<=0)return 0;
        float scale=Math.min(1f,Math.min(1200f/width,1900f/height));
        return Math.max(1,Math.round(width*scale));
    }
    static int targetHeight(int width,int height){
        if(width<=0||height<=0)return 0;
        float scale=Math.min(1f,Math.min(1200f/width,1900f/height));
        return Math.max(1,Math.round(height*scale));
    }
    static void capture(Activity activity,View page,Consumer<Bitmap> callback){
        capture(activity,page,null,callback);
    }
    /**
     * Crop to the real visible book page rather than the entire reader.
     * The supplied rectangle is in page-local coordinates, clipped to view.
     */
    static void capture(Activity activity,View page,RectF region,
                        Consumer<Bitmap> callback){
        int w=page.getWidth(),h=page.getHeight();
        if(w<=0||h<=0 || !page.isAttachedToWindow()){
            callback.accept(null);
            return;
        }
        final RectF crop=region==null?new RectF(0,0,w,h):new RectF(region);
        if(!crop.intersect(0,0,w,h)||crop.width()<2||crop.height()<2){
            callback.accept(null);return;
        }
        final int areaW=Math.round(crop.width()),areaH=Math.round(crop.height());
        final int targetW=targetWidth(areaW,areaH),
                targetH=targetHeight(areaW,areaH);
        final Bitmap result;
        try {
            result=Bitmap.createBitmap(targetW,targetH,Bitmap.Config.ARGB_8888);
        }catch(OutOfMemoryError memory){
            callback.accept(null);
            return;
        }
        int[] xy=new int[2];
        page.getLocationInWindow(xy);
        Rect source=new Rect(
                xy[0]+Math.round(crop.left),xy[1]+Math.round(crop.top),
                xy[0]+Math.round(crop.right),xy[1]+Math.round(crop.bottom));
        Handler ui=new Handler(Looper.getMainLooper());
        AtomicBoolean delivered=new AtomicBoolean(false);
        Runnable fallback=()->{
            if(!delivered.compareAndSet(false,true))return;
            // A pending PixelCopy may still own result. Do not recycle it
            // until its callback arrives, to avoid a native copy into a
            // recycled Bitmap on slower or overloaded Android devices.
            try{
                Bitmap software=Bitmap.createBitmap(targetW,targetH,
                        Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(software);
                canvas.scale((float)targetW/crop.width(),
                        (float)targetH/crop.height());
                canvas.translate(-crop.left,-crop.top);
                page.draw(canvas);
                callback.accept(software);
            }catch(Throwable failed){
                callback.accept(null);
            }
        };
        try{
            PixelCopy.request(activity.getWindow(),source,result,code->{
                if(!delivered.compareAndSet(false,true)){
                    result.recycle();
                    return;
                }
                if(code==PixelCopy.SUCCESS)callback.accept(result);
                else{
                    result.recycle();
                    try{
                        Bitmap software=Bitmap.createBitmap(targetW,targetH,
                                Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(software);
                        canvas.scale((float)targetW/crop.width(),
                                (float)targetH/crop.height());
                        canvas.translate(-crop.left,-crop.top);
                        page.draw(canvas);
                        callback.accept(software);
                    }catch(Throwable failed){callback.accept(null);}
                }
            },ui);
            // Avoid leaving the reader blocked if a device drops PixelCopy's
            // callback. Late completions are discarded by the atomic gate.
            ui.postDelayed(fallback,800);
        }catch(Exception unsupported){
            result.recycle();
            fallback.run();
        }
    }
}
