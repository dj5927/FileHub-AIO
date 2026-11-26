package com.nasmount.mobile;

import android.app.Activity;
import android.content.Context;

/** App-local language; does not modify the Android device language. */
abstract class LocalizedActivity extends Activity {
    private FlutterViewerChrome viewerChrome;
    private boolean viewerResumed;
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(L10n.localizedContext(base));
    }
    protected void installViewerChrome(android.widget.LinearLayout bar,
            java.util.function.Supplier<java.util.Map<String,Object>> state,
            java.util.function.BooleanSupplier gate,
            java.util.function.BiConsumer<String,Integer> dispatch) {
        if(!FlutterUiMode.enabled(this)||viewerChrome!=null)return;
        viewerChrome=new FlutterViewerChrome(this,bar,viewerResumed,state,gate,dispatch);
    }
    protected android.widget.LinearLayout viewerNativeBar(android.widget.LinearLayout original){
        return viewerChrome==null?original:viewerChrome.nativeBar();
    }
    @Override protected void onResume(){
        super.onResume();viewerResumed=true;if(viewerChrome!=null)viewerChrome.resume();
    }
    @Override protected void onPause(){
        viewerResumed=false;if(viewerChrome!=null)viewerChrome.pause();super.onPause();
    }
    @Override protected void onDestroy(){
        if(viewerChrome!=null){viewerChrome.close();viewerChrome=null;}super.onDestroy();
    }
}
