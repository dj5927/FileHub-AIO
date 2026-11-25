package com.nasmount.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/** Self-contained QR camera view. No external scanner app is required. */
public final class QrScanActivity extends Activity
        implements TextureView.SurfaceTextureListener {
    static final String EXTRA_QR="filehub_qr_scan_result";
    private static final int REQUEST_CAMERA=7214;
    private TextureView preview;
    private HandlerThread thread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    private volatile boolean opening;
    private final AtomicBoolean found=new AtomicBoolean(false);
    private long lastDecode;

    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        LinearLayout content=Ui.vertical(this);
        content.setBackgroundColor(0xFF101519);
        content.setPadding(Ui.dp(this,12),Ui.dp(this,15),
                Ui.dp(this,12),Ui.dp(this,18));
        setContentView(content);
        TextView heading=Ui.text(this,"LAN QR 스캔",20,0xFFFFFFFF,true);
        heading.setGravity(Gravity.CENTER);
        content.addView(heading,Ui.lp(this,-1,62));
        TextView help=Ui.text(this,
                "받는 TV의 QR코드를 화면 안에 맞추세요.\n" +
                "인식되면 같은 내부망에서 설정을 암호화해 전송합니다.",
                14,0xFFE2E8EB,false);
        help.setGravity(Gravity.CENTER);
        content.addView(help,Ui.lp(this,-1,80));
        preview=new TextureView(this);
        preview.setSurfaceTextureListener(this);
        content.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        TextView cancel=Ui.text(this,L10n.t("취소"),17,0xFF66D4C6,true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(v->finish());
        content.addView(cancel,Ui.lp(this,-1,62));
    }

    @Override protected void onResume() {
        super.onResume();
        found.set(false);
        thread=new HandlerThread("filehub-qr-camera");
        thread.start();
        cameraHandler=new Handler(thread.getLooper());
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA},REQUEST_CAMERA);
        else if(preview.isAvailable())openCamera();
    }

    @Override public void onRequestPermissionsResult(
            int code,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code!=REQUEST_CAMERA)return;
        if(grants.length==0||grants[0]!=PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"카메라 권한이 필요합니다.",Toast.LENGTH_LONG).show();
            finish();
        }else if(preview.isAvailable())openCamera();
    }

    private void openCamera() {
        if(opening || camera!=null || cameraHandler==null || isFinishing())return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        opening=true;
        try {
            CameraManager cameras=(CameraManager)getSystemService(CAMERA_SERVICE);
            String id=null;
            for(String candidate:cameras.getCameraIdList()){
                Integer direction=cameras.getCameraCharacteristics(candidate)
                        .get(CameraCharacteristics.LENS_FACING);
                if(id==null)id=candidate;
                if(direction!=null&&direction==CameraCharacteristics.LENS_FACING_BACK){
                    id=candidate;break;
                }
            }
            if(id==null)throw new CameraAccessException(
                    CameraAccessException.CAMERA_ERROR,"Camera unavailable");
            StreamConfigurationMap map=cameras.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            android.util.Size desired=new android.util.Size(640,480);
            if(map!=null){
                android.util.Size[] options=map.getOutputSizes(ImageFormat.YUV_420_888);
                if(options!=null&&options.length>0){
                    desired=options[0];
                    int best=Integer.MAX_VALUE;
                    for(android.util.Size option:options){
                        int score=Math.abs(option.getWidth()-640)+
                                Math.abs(option.getHeight()-480);
                        if(score<best){best=score;desired=option;}
                    }
                }
            }
            reader=ImageReader.newInstance(desired.getWidth(),desired.getHeight(),
                    ImageFormat.YUV_420_888,2);
            reader.setOnImageAvailableListener(this::onFrame,cameraHandler);
            SurfaceTexture texture=preview.getSurfaceTexture();
            if(texture==null)throw new IllegalStateException("Preview unavailable");
            texture.setDefaultBufferSize(desired.getWidth(),desired.getHeight());
            previewSurface=new Surface(texture);
            cameras.openCamera(id,new CameraDevice.StateCallback(){
                @Override public void onOpened(CameraDevice device){
                    camera=device;opening=false;startPreview();
                }
                @Override public void onDisconnected(CameraDevice device){
                    device.close();camera=null;opening=false;
                }
                @Override public void onError(CameraDevice device,int error){
                    device.close();camera=null;opening=false;
                    runOnUiThread(()->{if(!isFinishing())showFailure();});
                }
            },cameraHandler);
        }catch(Exception error){opening=false;showFailure();}
    }

    private void startPreview() {
        if(camera==null||reader==null||previewSurface==null)return;
        try {
            CaptureRequest.Builder builder=camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            camera.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),
                    new CameraCaptureSession.StateCallback(){
                        @Override public void onConfigured(CameraCaptureSession active){
                            session=active;
                            try {
                                active.setRepeatingRequest(builder.build(),null,cameraHandler);
                            }catch(Exception e){
                                runOnUiThread(()->{if(!isFinishing())showFailure();});
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession active){
                            runOnUiThread(()->{if(!isFinishing())showFailure();});
                        }
                    },cameraHandler);
        }catch(Exception error){
            runOnUiThread(()->{if(!isFinishing())showFailure();});
        }
    }

    private void onFrame(ImageReader source) {
        try(Image frame=source.acquireLatestImage()){
            if(frame==null||found.get()||
                    SystemClock.elapsedRealtime()-lastDecode<280)return;
            lastDecode=SystemClock.elapsedRealtime();
            Image.Plane yPlane=frame.getPlanes()[0];
            int width=frame.getWidth(),height=frame.getHeight();
            if(width>1400||height>1400)return;
            byte[] luminance=new byte[width*height];
            ByteBuffer bytes=yPlane.getBuffer();
            int rowStride=yPlane.getRowStride(),pixelStride=yPlane.getPixelStride();
            for(int row=0;row<height;row++){
                int start=row*rowStride;
                for(int col=0;col<width;col++)
                    luminance[row*width+col]=bytes.get(start+col*pixelStride);
            }
            String value=QrCameraDecoder.decode(luminance,width,height);
            if(value!=null && value.startsWith(BuildConfig.PAIR_SCHEME + "://pair?") &&
                    found.compareAndSet(false,true)){
                runOnUiThread(()->{
                    if(isFinishing())return;
                    Intent answer=new Intent();
                    answer.putExtra(EXTRA_QR,value);
                    setResult(RESULT_OK,answer);
                    finish();
                });
            }
        }catch(Exception ignored){}
    }

    private void showFailure(){
        Toast.makeText(this,"카메라를 열 수 없습니다.",Toast.LENGTH_LONG).show();
        finish();
    }

    @Override protected void onPause(){
        if(session!=null){session.close();session=null;}
        if(camera!=null){camera.close();camera=null;}
        if(reader!=null){reader.close();reader=null;}
        if(previewSurface!=null){previewSurface.release();previewSurface=null;}
        opening=false;
        if(thread!=null){thread.quitSafely();thread=null;cameraHandler=null;}
        super.onPause();
    }
    @Override public void onSurfaceTextureAvailable(
            SurfaceTexture texture,int width,int height){openCamera();}
    @Override public void onSurfaceTextureSizeChanged(
            SurfaceTexture texture,int width,int height){}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
}
