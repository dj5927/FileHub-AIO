package com.nasmount.mobile;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.BiConsumer;

/**
 * One bounded Flutter surface per viewer. The original parent keeps its
 * geometry, alpha and visibility, including Media3/PiP. Native controls
 * remain a complete fallback. No document contents or paths cross the bridge.
 */
final class FlutterViewerChrome implements FlutterNavigationSurface.Host {
    private final LocalizedActivity activity;
    private final LinearLayout bar,fallback;
    private final String nonce=UUID.randomUUID().toString();
    private final Supplier<Map<String,Object>> state;
    private final BooleanSupplier gate;
    private final BiConsumer<String,Integer> dispatch;
    private final Handler main=new Handler(Looper.getMainLooper());
    private FlutterNavigationSurface surface;
    private Map<String,Object> last=Collections.emptyMap();
    private boolean resumed,closed;
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(closed||!resumed)return;
            if(surface!=null&&surface.isShown()&&bar.isShown()&&bar.getAlpha()>.05f){
                try{
                    Map<String,Object> now=viewerState();
                    if(!now.equals(last)){last=now;surface.push("viewerUpdate",now);}
                }catch(RuntimeException failure){close();return;}
            }
            main.postDelayed(this,250);
        }
    };
    FlutterViewerChrome(LocalizedActivity activity,LinearLayout bar,boolean resumed,
                       Supplier<Map<String,Object>> state,BooleanSupplier gate,
                       BiConsumer<String,Integer> dispatch){
        this.activity=activity;this.bar=bar;this.resumed=resumed;
        this.state=state;this.gate=gate;this.dispatch=dispatch;
        fallback=new LinearLayout(activity);
        fallback.setOrientation(bar.getOrientation());fallback.setGravity(bar.getGravity());
        while(bar.getChildCount()>0){
            View child=bar.getChildAt(0);
            ViewGroup.LayoutParams lp=child.getLayoutParams();
            bar.removeViewAt(0);fallback.addView(child,lp);
        }
        FrameLayout frame=new FrameLayout(activity);
        frame.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        bar.addView(frame,new LinearLayout.LayoutParams(-1,-1));
        surface=new FlutterNavigationSurface(activity,frame,fallback,FlutterUiContract.VIEWER,this,resumed);
        if(resumed)main.post(ticker);
    }
    LinearLayout nativeBar(){return fallback;}
    private boolean live(){
        return !closed&&!activity.isFinishing()&&!activity.isDestroyed()&&
                bar.isShown()&&bar.getAlpha()>.05f&&resumed;
    }
    private Map<String,Object> viewerState(){
        Map<String,Object> data=new LinkedHashMap<>(state.get());
        data.put("nonce",nonce);
        return data;
    }
    @Override public Map<String,Object> snapshot(String route){
        if(closed)throw new IllegalStateException("closed viewer");
        Map<String,String> labels=FlutterOperationSnapshot.labels();
        labels.put("back",L10n.t("뒤로"));
        labels.put("previous",L10n.t("이전"));
        labels.put("next",L10n.t("다음"));
        labels.put("play",L10n.t("재생"));
        labels.put("pause",L10n.t("일시정지"));
        labels.put("settings",L10n.t("설정"));
        labels.put("quick",L10n.t("빠른 이동"));
        labels.put("classicViewer",L10n.t("기존 뷰어 조작부"));
        labels.put("save",L10n.t("저장"));
        labels.put("search",L10n.t("현재 시트 검색"));
        labels.put("readOnly",L10n.t("읽기 전용"));
        labels.put("saving",L10n.t("저장 진행 중"));
        Map<String,Object> result=FlutterOperationSnapshot.base(activity,route,labels);
        result.put("viewer",viewerState());
        return result;
    }
    @Override public void action(String route,String action,String id){}
    @Override public void reorderFavorites(Object order){throw new IllegalStateException("viewer");}
    @Override public void explorerCall(MethodCall call,MethodChannel.Result result){
        if(!"viewerCommand".equals(call.method)||!(call.arguments instanceof Map)||!live()||
                surface==null||!surface.isShown()){
            result.error("STALE_VIEWER",null,null);return;
        }
        Object command=call.argument("command"),value=call.argument("value");
        Map<String,Object> current=viewerState();
        if(!ViewerUiPolicy.accepts(nonce,call.argument("nonce"),command,value,
                (Collection<?>)current.get("enabled"))||(!"classic".equals(command)&&!gate.getAsBoolean())){
            result.error("VIEWER_UNAVAILABLE",null,null);return;
        }
        result.success(null);
        main.post(()->{
            if(!live())return;
            if("classic".equals(command)){close();return;}
            if(!gate.getAsBoolean())return;
            Map<String,Object> updated=viewerState();
            if(!ViewerUiPolicy.accepts(nonce,nonce,command,value,(Collection<?>)updated.get("enabled")))return;
            dispatch.accept((String)command,(Integer)value);
        });
    }
    @Override public void flutterShown(){last=Collections.emptyMap();}
    @Override public void fallbackShown(){main.removeCallbacks(ticker);}
    void resume(){
        resumed=true;
        if(surface!=null&&surface.isActive()){
            surface.resume();main.removeCallbacks(ticker);main.post(ticker);
        }
    }
    void pause(){resumed=false;main.removeCallbacks(ticker);if(surface!=null)surface.pause();}
    void close(){
        if(closed)return;
        closed=true;main.removeCallbacksAndMessages(null);
        if(surface!=null){surface.close();surface=null;}
    }
}
