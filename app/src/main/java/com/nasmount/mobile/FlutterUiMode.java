package com.nasmount.mobile;

import android.content.Context;

/** Presentation preference only; never clears connection or reader data. */
final class FlutterUiMode {
    private static final String PREFS="filehub_flutter_ui";
    private FlutterUiMode(){}
    static boolean enabled(Context context){
        // Keep native TV and automatic recovery, but no user-facing classic mode.
        return !TvMode.isTv(context);
    }
    static void set(Context context,boolean enabled){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putBoolean("enabled",enabled).apply();
    }
    static void prewarm(Context context){
        if(!enabled(context))return;
        try{
            io.flutter.FlutterInjector.instance().flutterLoader()
                    .startInitialization(context.getApplicationContext());
        }catch(RuntimeException|LinkageError ignored){}
    }
}
