package com.nasmount.mobile;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import io.flutter.embedding.android.FlutterTextureView;

/** Observe actual TextureView buffers, preserving Flutter's own listener. */
final class BufferedFlutterTextureView extends FlutterTextureView {
    BufferedFlutterTextureView(Context context,Runnable updated,Runnable lost){
        super(context);
        setOpaque(false);
        final TextureView.SurfaceTextureListener delegate=getSurfaceTextureListener();
        setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h){
                lost.run();delegate.onSurfaceTextureAvailable(s,w,h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h){
                delegate.onSurfaceTextureSizeChanged(s,w,h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s){
                lost.run();return delegate.onSurfaceTextureDestroyed(s);
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s){
                delegate.onSurfaceTextureUpdated(s);updated.run();
            }
        });
    }
}
