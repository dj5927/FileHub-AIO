package com.nasmount.mobile;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.*;
final class EditionApkInstaller {
    private final MainActivity a;
    private static final int REQ_APK_INSTALL_AUTH=507;
    private File pendingApkInstall;
    EditionApkInstaller(MainActivity activity) { a=activity; }
    void restore(Bundle state) {
        if (state == null) return;
        String queued = state.getString("pending_apk_install", "");
        if (!queued.isEmpty() && queued.startsWith(new File(a.getCacheDir(), "apk_install")
                .getAbsolutePath() + File.separator)) pendingApkInstall = new File(queued);
    }
    void save(Bundle state) {
        if (pendingApkInstall != null)
            state.putString("pending_apk_install", pendingApkInstall.getAbsolutePath());
    }
    boolean handleResult(int requestCode) {
        if (requestCode != REQ_APK_INSTALL_AUTH) return false;
        if (pendingApkInstall != null) {
            if (a.getPackageManager().canRequestPackageInstalls())
                launchSystemApkInstaller(pendingApkInstall);
            else { pendingApkInstall=null; a.toast(L10n.t("APK 설치 권한이 허용되지 않아 취소했습니다.")); }
        }
        return true;
    }
    void stage(String name, MainActivity.Job<InputStream> openSource) {
        a.runJob(L10n.t("설치 패키지 준비 중..."), () -> {
            try (InputStream input = openSource.execute()) {
                if (input == null) throw new IOException("APK 파일을 열 수 없습니다.");
                return ApkInstallStaging.stage(input, a.getCacheDir());
            }
        }, staged -> {
            android.content.pm.PackageInfo pkg =
                    a.getPackageManager().getPackageArchiveInfo(staged.getAbsolutePath(), 0);
            if (pkg == null || pkg.packageName == null || pkg.packageName.isEmpty()) {
                a.showError(L10n.t("Android 패키지 정보를 확인할 수 없습니다. ") +
                        "단독 APK 설치 파일인지 확인해 주세요.");
                return;
            }
            new AlertDialog.Builder(a)
                    .setTitle(L10n.t("APK 설치"))
                    .setMessage(L10n.t("파일: ") + name +
                            "\n패키지: " + pkg.packageName +
                            "\n버전: " + pkg.versionName +
                            "\n크기: " + android.text.format.Formatter.formatFileSize(
                                    a, staged.length()) +
                            "\n\n신뢰할 수 있는 APK만 설치하세요. " +
                            "최종 설치 여부는 Android 설치 관리자에서 결정합니다.")
                    .setNegativeButton(L10n.t("취소"), null)
                    .setPositiveButton(L10n.t("설치 화면 열기"),
                            (dialog, which) -> requestPackageInstall(staged))
                    .show();
        });
    }

    private void requestPackageInstall(File staged) {
        pendingApkInstall = staged;
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle(L10n.t("NAS Mount의 APK 설치 권한"))
                    .setMessage(L10n.t("Android 설정에서 '이 출처의 앱 허용'을 켜야 ") +
                            "APK 설치 화면을 열 수 있습니다. 설정에서 돌아오면 계속합니다.")
                    .setNegativeButton(L10n.t("취소"), (d, w) -> pendingApkInstall = null)
                    .setPositiveButton(L10n.t("설정 열기"), (d, w) -> {
                        try {
                            Intent settings = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName()));
                            a.startActivityForResult(settings, REQ_APK_INSTALL_AUTH);
                        } catch (ActivityNotFoundException unavailable) {
                            a.showError(L10n.t("이 기기의 앱 설치 허용 화면을 열 수 없습니다."));
                        }
                    }).show();
            return;
        }
        launchSystemApkInstaller(staged);
    }

    private void launchSystemApkInstaller(File staged) {
        if (staged == null || !staged.isFile()) {
            pendingApkInstall = null;
            a.showError(L10n.t("APK 파일이 사라졌습니다. 다시 선택해 주세요."));
            return;
        }
        try {
            ApkInstallStaging.verifyArchive(staged);
            Uri share = FileProvider.getUriForFile(a,
                    a.getPackageName() + ".files", staged);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setDataAndType(share, "application/vnd.android.package-archive");
            install.setClipData(android.content.ClipData.newUri(
                    a.getContentResolver(), "APK 설치 파일", share));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(install);
            } catch (ActivityNotFoundException oldInstaller) {
                install.setAction(Intent.ACTION_VIEW);
                a.startActivity(install);
            }
            pendingApkInstall = null;
        } catch (ActivityNotFoundException missing) {
            a.showError(L10n.t("시스템 패키지 설치 관리자를 찾지 못했습니다."));
        } catch (Exception failure) {
            a.showError(L10n.t("APK 설치 준비 오류: ") + a.safeError(failure));
        }
    }

}
