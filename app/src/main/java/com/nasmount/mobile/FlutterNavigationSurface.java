package com.nasmount.mobile;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.renderer.FlutterUiDisplayListener;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns a UI-only Flutter engine and a native fallback in one bounded surface.
 * MainActivity remains the owner of navigation, permissions and file I/O. */
final class FlutterNavigationSurface {
    interface Host {
        Map<String,Object> snapshot(String route);
        void action(String route, String action, String id);
        void reorderFavorites(Object order) throws Exception;
        default void explorerCall(MethodCall call,MethodChannel.Result result){result.notImplemented();}
        default void fallbackShown(){}
        default void flutterShown(){}
    }
    private final Activity activity;
    private final FrameLayout frame;
    private View fallback;
    private String route;
    private Host host;
    private Host drawerHost;
    private final boolean persistent;
    private final FlutterFrameGate gate=new FlutterFrameGate();
    private boolean bound=true;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FlutterEngine engine;
    private FlutterView view;
    private MethodChannel channel;
    private boolean closed, resumed, shown, rendered, ready, starting, retired;
    private final Runnable timeout = () -> { if (!shown) failSurface(); };
    private final FlutterUiDisplayListener firstFrame = new FlutterUiDisplayListener() {
        @Override public void onFlutterUiDisplayed() {
            rendered = true;
            showWhenReady();
        }
        @Override public void onFlutterUiNoLongerDisplayed() {rendered=false;gate.lost();}
    };
    private void showWhenReady() {
        if (closed || retired || !bound || shown || !resumed || !rendered ||
                !gate.canReveal() || view==null) return;
        shown = true;
        main.removeCallbacks(timeout);
        fallback.setVisibility(persistent?View.INVISIBLE:View.GONE);
        view.setAlpha(1f);
        view.bringToFront();
        host.flutterShown();
    }

    FlutterNavigationSurface(Activity activity, FrameLayout frame, View fallback,
                             String route, Host host, boolean resumed) {
        this(activity,frame,fallback,route,host,resumed,false);
    }
    FlutterNavigationSurface(Activity activity, FrameLayout frame, View fallback,
                             String route, Host host, boolean resumed,boolean persistent) {
        this.activity = activity;
        this.frame = frame;
        this.fallback = fallback;
        this.route = route;
        this.host = host;
        this.resumed = resumed;
        this.persistent=persistent;
        gate.next();
        frame.post(this::start);
    }
    private void failSurface(){
        if(!persistent){close();return;}
        Host failedHost=host;
        if(fallback!=null)fallback.setVisibility(View.VISIBLE);
        unbind();
        if(failedHost!=null)failedHost.fallbackShown();
    }
    void unbind(){
        if(closed||!persistent)return;
        if(fallback!=null)fallback.setVisibility(View.VISIBLE);
        closeDrawerOverlay();
        bound=false;shown=false;ready=false;gate.next();
        fallback=null;host=null;route=null;
        main.removeCallbacks(timeout);
    }
    void rebind(View fallback,String route,Host host,boolean resumed){
        if(closed||!persistent)throw new IllegalStateException("surface unavailable");
        unbind();
        this.fallback=fallback;this.route=route;this.host=host;
        this.resumed=resumed;bound=true;
        // Keep the existing texture and Dart isolate attached. The old
        // model is not actionable while the new route is being painted.
        if(engine==null){frame.post(this::start);return;}
        if(resumed)engine.getLifecycleChannel().appIsResumed();
        channel.invokeMethod("routeChanged",gate.epoch());
        if(resumed)main.postDelayed(timeout,10000);
    }
    private void start() {
        if(closed||retired||!bound||starting||engine!=null||!resumed)return;
        if (closed || activity.isFinishing() || activity.isDestroyed() || !frame.isAttachedToWindow()) {
            close(); return;
        }
        if(!FlutterUiMode.enabled(activity)){close();return;}
        starting=true;
        try {
            engine = new FlutterEngine(activity);
            channel = new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), FlutterUiContract.CHANNEL);
            channel.setMethodCallHandler(this::handle);
            view = new FlutterView(activity, new BufferedFlutterTextureView(activity,()->{
                gate.buffer();showWhenReady();
            },()->{gate.lost();})){
                @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
                    return shown&&bound&&super.dispatchTouchEvent(event);
                }
            };
            view.addOnFirstFrameRenderedListener(firstFrame);
            // Native fallback remains on top until a real Flutter frame arrives.
            // A near-transparent view still receives real texture updates.
            // An opaque native sibling above it can prevent those callbacks.
            view.setAlpha(.01f);
            frame.addView(view, new FrameLayout.LayoutParams(-1,-1));
            view.attachToFlutterEngine(engine);
            engine.getDartExecutor().executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault());
            if (resumed) engine.getLifecycleChannel().appIsResumed();
            else engine.getLifecycleChannel().appIsPaused();
            main.postDelayed(timeout, 10000);
        } catch (RuntimeException | LinkageError failure) {
            // Do not expose storage credentials or exception details in logs.
            android.util.Log.w("FileHubFlutter", "Navigation surface unavailable; native fallback");
            close();
        } finally {
            starting=false;
        }
    }
    private void handle(MethodCall call, MethodChannel.Result result) {
        if (closed||retired||!bound) { result.error("CLOSED",null,null); return; }
        final long requestEpoch=gate.epoch();
        final Host requestHost=host;
        final String requestRoute=route;
        if("drawerAction".equals(call.method)||"drawerReorder".equals(call.method)){
            handleDrawer(call,result,requestEpoch);return;
        }
        // Explorer tiles request passive metadata while Flutter builds its
        // first frame. Their opaque IDs/revisions are validated by the live
        // host. Only these read-only requests may precede frame readiness;
        // all navigation and file mutation commands remain blocked.
        boolean passive=FlutterUiContract.passiveExplorerRequest(route,call.method);
        if(drawerHost!=null&&!passive&&!"snapshot".equals(call.method)&&!"uiReady".equals(call.method)){
            result.error("DRAWER_OPEN",null,null);return;
        }
        if(persistent&&!passive&&!"snapshot".equals(call.method)&&!"uiReady".equals(call.method)&&!shown){
            result.error("ROUTE_NOT_READY",null,null);return;
        }
        switch (call.method) {
            case "uiReady":
                if(!(call.arguments instanceof Number)||!gate.ready(((Number)call.arguments).longValue())){
                    result.error("STALE_FRAME",null,null);return;
                }
                ready = true;
                showWhenReady();
                result.success(null);
                return;
            case "snapshot":
                if (FlutterUiContract.EXPLORER.equals(route)||FlutterUiContract.SHELF.equals(route)||FlutterUiContract.TRANSFER.equals(route)||
                        FlutterUiContract.FILE_PROMPT.equals(route)||FlutterUiContract.VIEWER.equals(route)||
                        FlutterUiContract.PANEL.equals(route)) {
                    try {result.success(withEpoch(host.snapshot(route),requestEpoch));}
                    catch(Exception failure){result.error("SNAPSHOT_FAILED",null,null);}
                    return;
                }
                worker.execute(() -> {
                    try {
                        Map<String,Object> data = withEpoch(requestHost.snapshot(requestRoute),requestEpoch);
                        main.post(() -> {
                            if (!closed&&!retired&&bound&&gate.epoch()==requestEpoch) result.success(data);
                            else result.error("STALE_ROUTE",null,null);
                        });
                    } catch (Exception failure) {
                        main.post(() -> result.error("SNAPSHOT_FAILED",null,null));
                    }
                });
                return;
            case "action":
                if (!(call.arguments instanceof Map)) {
                    result.error("INVALID_ACTION",null,null); return;
                }
                Object rawName=call.argument("name"), rawId=call.argument("id");
                if(persistent&&(!(call.argument("epoch") instanceof Number)||
                        ((Number)call.argument("epoch")).longValue()!=requestEpoch)){
                    result.error("STALE_ROUTE",null,null);return;
                }
                String name=rawName instanceof String ? (String)rawName : null;
                String id=rawId instanceof String ? (String)rawId : null;
                if ((rawId != null && id == null) || !FlutterUiContract.validNavigationAction(route,name,id)) {
                    result.error("INVALID_ACTION",null,null); return;
                }
                if ("toggleFavorites".equals(name) || "toggleNetwork".equals(name)) {
                    host.action(route,name,id);
                    result.success(null);
                    return;
                }
                if (FlutterUiContract.EXPLORER.equals(route) && "entrySelect".equals(name)) {
                    host.action(route,name,id);
                    result.success(null);
                    return;
                }
                if (FlutterUiContract.EXPLORER.equals(route)&&
                        ("selectAll".equals(name)||"clearSelection".equals(name)||"copySelected".equals(name)||
                         "moveSelected".equals(name)||"clearClipboard".equals(name))) {
                    host.action(route,name,id);
                    result.success(null);
                    return;
                }
                result.success(null);
                // Never tear down an engine while processing its own reply.
                main.post(() -> { if (!closed&&!retired&&bound&&gate.epoch()==requestEpoch)
                    requestHost.action(requestRoute,name,id); });
                return;
            case "reorderFavorites":
                if (!FlutterUiContract.DRAWER.equals(route) || !(call.arguments instanceof java.util.List)) {
                    result.error("INVALID_ORDER",null,null); return;
                }
                try {
                    host.reorderFavorites(call.arguments);
                    result.success(null);
                } catch (Exception failure) {
                    result.error("STALE_ORDER",null,null);
                }
                return;
            default:
                if(FlutterUiContract.EXPLORER.equals(route)||FlutterUiContract.SHELF.equals(route)||FlutterUiContract.FILE_PROMPT.equals(route)||
                        FlutterUiContract.VIEWER.equals(route)||FlutterUiContract.PANEL.equals(route)||
                        FlutterUiContract.CONNECTIONS.equals(route))
                    host.explorerCall(call,result);
                else result.notImplemented();
        }
    }
    boolean showDrawerOverlay(Host overlay){
        if(!persistent||!isShown()||channel==null||drawerHost!=null)return false;
        try{
            Map<String,Object> data=withEpoch(overlay.snapshot(FlutterUiContract.DRAWER),gate.epoch());
            drawerHost=overlay;
            channel.invokeMethod("drawerOpen",data);
            return true;
        }catch(RuntimeException failure){drawerHost=null;return false;}
    }
    void closeDrawerOverlay(){
        if(drawerHost==null)return;
        drawerHost=null;
        if(channel!=null)channel.invokeMethod("drawerClose",null);
    }
    private void handleDrawer(MethodCall call,MethodChannel.Result result,long epoch){
        Host overlay=drawerHost;
        if(!isShown()||overlay==null||!(call.arguments instanceof Map)||
                !(call.argument("epoch") instanceof Number)||
                ((Number)call.argument("epoch")).longValue()!=epoch){
            result.error("STALE_DRAWER",null,null);return;
        }
        if("drawerReorder".equals(call.method)){
            try{
                overlay.reorderFavorites(call.argument("order"));
                channel.invokeMethod("drawerOpen",withEpoch(overlay.snapshot(FlutterUiContract.DRAWER),epoch));
                result.success(null);
            }catch(Exception error){result.error("STALE_ORDER",null,null);}
            return;
        }
        Object rawAction=call.argument("name"),rawId=call.argument("id");
        String action=rawAction instanceof String?(String)rawAction:null;
        String id=rawId instanceof String?(String)rawId:null;
        if((rawId!=null&&id==null)||!FlutterUiContract.validNavigationAction(FlutterUiContract.DRAWER,action,id)){
            result.error("INVALID_DRAWER_ACTION",null,null);return;
        }
        result.success(null);
        main.post(()->{
            if(!isShown()||drawerHost!=overlay||gate.epoch()!=epoch)return;
            overlay.action(FlutterUiContract.DRAWER,action,id);
            if(drawerHost==overlay&&("toggleFavorites".equals(action)||"toggleNetwork".equals(action)))
                channel.invokeMethod("drawerOpen",withEpoch(overlay.snapshot(FlutterUiContract.DRAWER),epoch));
        });
    }
    private Map<String,Object> withEpoch(Map<String,Object> data,long epoch){
        Map<String,Object> copy=new java.util.LinkedHashMap<>(data);
        copy.put("surfaceEpoch",epoch);return copy;
    }
    void resume() {
        resumed = true;
        if(closed||retired)return;
        if(engine==null){
            frame.post(this::start);
        }else {
            engine.getLifecycleChannel().appIsResumed();
            showWhenReady();
            main.removeCallbacks(timeout);
            if(bound&&!shown)main.postDelayed(timeout,10000);
            refresh();
        }
    }
    void pause() {
        resumed = false;
        main.removeCallbacks(timeout);
        if (!closed && engine != null) engine.getLifecycleChannel().appIsPaused();
    }
    void refresh() {
        if (!closed && !retired && bound && channel != null) channel.invokeMethod("refresh",null);
    }
    void push(String method,Object payload){
        if(!closed&&!retired&&channel!=null)channel.invokeMethod(method,payload);
    }
    boolean isShown(){return shown&&!closed&&!retired&&bound;}
    boolean isActive(){return !closed&&!retired&&bound;}
    boolean isClosed(){return closed;}
    void dimUntilReady(){if(view!=null&&!shown)view.setAlpha(.01f);}
    /** Keep the already-rendered texture attached during one visual handoff.
     * Its obsolete host commands and background work are immediately disabled. */
    void retire(){
        if(closed||retired)return;
        retired=true;
        pause();
        worker.shutdownNow();
        if(channel!=null)channel.setMethodCallHandler(null);
    }
    void close() {
        if (closed) return;
        closeDrawerOverlay();
        closed = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (channel != null) channel.setMethodCallHandler(null);
        if (view != null) {
            view.removeOnFirstFrameRenderedListener(firstFrame);
            try { view.detachFromFlutterEngine(); } catch (RuntimeException ignored) {}
            if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
        }
        if (engine != null) {
            try { engine.getLifecycleChannel().appIsDetached(); engine.destroy(); }
            catch (RuntimeException ignored) {}
        }
        if(fallback!=null)fallback.setVisibility(View.VISIBLE);
        if(!retired&&host!=null)host.fallbackShown();
        channel=null; view=null; engine=null;
    }
}
