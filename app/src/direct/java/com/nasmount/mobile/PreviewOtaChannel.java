package com.nasmount.mobile;

import android.content.Context;

/** Explicit opt-in; stable and private OTA keep their existing behavior. */
final class PreviewOtaChannel {
    private PreviewOtaChannel(){}
    static boolean enabled(Context c){
        return c.getSharedPreferences("filehub_preview_ota",Context.MODE_PRIVATE).getBoolean("enabled",false);
    }
    static void set(Context c,boolean enabled){
        c.getSharedPreferences("filehub_preview_ota",Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();
    }
}
