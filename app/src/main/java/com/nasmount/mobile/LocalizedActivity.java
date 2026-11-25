package com.nasmount.mobile;

import android.app.Activity;
import android.content.Context;

/** App-local language; does not modify the Android device language. */
abstract class LocalizedActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(L10n.localizedContext(base));
    }
}
