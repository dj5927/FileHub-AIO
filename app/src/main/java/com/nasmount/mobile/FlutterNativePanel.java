package com.nasmount.mobile;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.Map;
import java.util.function.Supplier;

/** Hosts settings and external Office workflow UI without moving native logic. */
final class FlutterNativePanel implements FlutterNavigationSurface.Host {
    private final Activity activity;
    private final Supplier<NativePanelModel> model;
    private final Handler main=new Handler(Looper.getMainLooper());
    private FlutterNavigationSurface surface;
    private boolean resumed,closed;
    FlutterNativePanel(Activity activity,FrameLayout frame,View fallback,
                       Supplier<NativePanelModel> model){
        this.activity=activity;this.model=model;
        surface=new FlutterNavigationSurface(activity,frame,fallback,FlutterUiContract.PANEL,this,false);
    }
    @Override public Map<String,Object> snapshot(String route){
        Map<String,String> labels=FlutterOperationSnapshot.labels();
        labels.put("classicPanel",L10n.t("기존 화면으로 전환"));
        labels.put("apply",L10n.t("적용"));
        Map<String,Object> result=FlutterOperationSnapshot.base(activity,route,labels);
        result.put("panel",model.get().snapshot());return result;
    }
    @Override public void action(String route,String action,String id){}
    @Override public void reorderFavorites(Object order){throw new IllegalStateException("panel");}
    private boolean live(){
        return resumed&&!closed&&!activity.isFinishing()&&!activity.isDestroyed()
                &&surface!=null&&surface.isShown();
    }
    @Override public void explorerCall(MethodCall call,MethodChannel.Result result){
        if(!"panelCommand".equals(call.method)||!(call.arguments instanceof Map)||!live()){
            result.error("STALE_PANEL",null,null);return;
        }
        Object revision=call.argument("revision"),id=call.argument("id"),value=call.argument("value");
        NativePanelModel current=model.get();
        if("classic".equals(id)&&value==null&&current.matches(revision)){
            result.success(null);main.post(this::close);return;
        }
        Runnable command=current.command(revision,id,value);
        if(command==null){result.error("STALE_PANEL_COMMAND",null,null);return;}
        result.success(null);
        main.post(()->{
            if(!live()||model.get()!=current)return;
            try{command.run();}
            catch(RuntimeException failure){close();}
        });
    }
    void refresh(){if(surface!=null)surface.refresh();}
    void resume(){resumed=true;if(surface!=null)surface.resume();}
    void pause(){resumed=false;if(surface!=null)surface.pause();}
    void close(){
        if(closed)return;closed=true;main.removeCallbacksAndMessages(null);
        if(surface!=null){surface.close();surface=null;}
    }
}
