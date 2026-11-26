package com.nasmount.mobile;

import android.app.Application;

public final class FileHubApplication extends Application {
    @Override protected void attachBaseContext(android.content.Context base){
        super.attachBaseContext(base);
        StartupCrashNote.install(this);
    }
    @Override public void onCreate() {
        super.onCreate();
        L10n.init(this);
        GoogleDriveAuth.init(this);
        EditionMonetization.init(this);
    }
}
