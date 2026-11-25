package com.nasmount.mobile;
import android.os.Bundle;
import java.io.InputStream;
// Keep APK files manageable but do not request installation permissions.
final class EditionApkInstaller {
    private final MainActivity a;
    EditionApkInstaller(MainActivity activity) { a=activity; }
    void restore(Bundle state) {}
    void save(Bundle state) {}
    boolean handleResult(int code) { return false; }
    void stage(String name, MainActivity.Job<InputStream> ignored) {
        a.showError(L10n.t("Play 버전에서는 APK 설치 기능을 제공하지 않습니다."));
    }
}
