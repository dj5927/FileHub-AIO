package com.nasmount.mobile;
import android.app.Activity;
// Play builds contain no updater, network endpoint, APK download or installer.
final class OtaUpdater {
    OtaUpdater(Activity ignored) {}
    void startupCheck() {}
    void manualCheck() {}
    void close() {}
}
