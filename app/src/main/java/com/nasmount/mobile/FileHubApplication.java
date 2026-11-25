package com.nasmount.mobile;

import android.app.Application;

public final class FileHubApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        L10n.init(this);
    }
}
