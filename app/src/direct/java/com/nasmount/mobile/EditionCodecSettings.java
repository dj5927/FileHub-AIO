package com.nasmount.mobile;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.TextView;
final class EditionCodecSettings {
    static void render(SettingsContent a) {
        a.section(L10n.t("코덱 · 호환성"), L10n.t("Media3 FFmpeg 확장 디코더가 APK에 포함됩니다. 기기 기본 디코더가 지원하지 않는 코덱은 FFmpeg를 자동으로 사용합니다."));
        String abi = android.os.Build.SUPPORTED_ABIS.length > 0 ? android.os.Build.SUPPORTED_ABIS[0] : "알 수 없음";
        String codecFolder = PlayerSettings.prefs(a).getString(
                PlayerSettings.CODEC_TREE_LABEL, "미지정");
        a.action(L10n.t("외부 코덱 폴더 가져오기"), codecFolder +
                "\n선택하면 .aar / .so / .zip을 NAS Mount 전용 폴더로 이동합니다.",
                () -> a.chooseFolder(SettingsContent.PICK_CODECS));
        a.note(L10n.t("기기 CPU: ") + abi +
                "\n기본 탑재: Media3 1.11.1 + NextLib FFmpeg 1.11.1-0.19.0" +
                "\n내장 FFmpeg 코덱: AC3, E-AC3, DTS, TrueHD, FLAC, ALAC, AAC 등" +
                "\nNAS Mount 보관 위치: " +
                CodecAddonManager.codecDirectory(a).getAbsolutePath() +
                "\n" + CodecAddonManager.inventory(a));
        a.note(L10n.t("기기 E-AC3 디코더: ") + a.detectNativeDecoder("audio/eac3") +
                "\n기기 Dolby TrueHD 디코더: " + a.detectNativeDecoder("audio/true-hd") +
                "\nMX Player 전용 파일을 가져오더라도 해당 파일을 실행하지 않습니다. 자동 재생에 쓰이는 FFmpeg는 검증된 APK 내장 모듈입니다. 선택 폴더의 파일은 복사 후 SHA256 검증을 거쳐 이동하고, 삭제 권한이 없으면 원본은 유지합니다.");

        a.section(L10n.t("동영상 코덱 라이선스"), L10n.t("동영상 플레이어에 포함된 확장 코덱의 라이선스입니다."));
        a.action(L10n.t("외부 플레이어 사용"), L10n.t("설치된 외부 플레이어로 동영상 재생 메뉴에서 연결할 수 있습니다."),
                () -> ExternalApps.playerStore(a.activity()));
        a.action(L10n.t("오픈소스 FFmpeg · NextLib 라이선스"),
                "NextLib FFmpeg 1.11.1-0.19.0 · GPLv3",
                () -> new AlertDialog.Builder(a).setTitle(L10n.t("FFmpeg 오픈소스 고지"))
                        .setMessage(L10n.t("NAS Mount의 확장 디코더는 NextLib GPLv3 기반입니다.\n\n") +
                                "프로젝트: https://github.com/anilbeesetti/nextlib\n\n" +
                                "라이선스: GNU General Public License version 3\n\n" +
                                "이 코덱이 포함된 앱을 다른 사람에게 배포할 때는 " +
                                "GPLv3의 소스 제공 및 저작권 고지 의무를 확인해야 합니다. " +
                                "네이티브 의존성의 라이선스도 APK에 포함되어 있습니다.")
                        .setPositiveButton(L10n.t("확인"), null).show());
    }
    static void importFolder(SettingsContent a, Uri folderUri) {
        TextView progress = a.label(L10n.t("선택 폴더의 코덱 파일을 확인 중..."), 13, Ui.TEXT);
        progress.setPadding(a.dp(24), a.dp(24), a.dp(24), a.dp(24));
        AlertDialog progressDialog = new AlertDialog.Builder(a)
                .setTitle(L10n.t("NAS Mount로 코덱 파일 가져오기"))
                .setView(progress)
                .setCancelable(false)
                .create();
        progressDialog.show();
        Thread worker = new Thread(() -> {
            try {
                CodecAddonManager.Result outcome = CodecAddonManager.importFromFolder(
                        a.getApplicationContext(), folderUri, (filename, index, total) ->
                                a.runOnUiThread(() -> progress.setText(
                                        index + "번째 파일 확인 중: " + filename)));
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed()) {
                        return;
                    }
                    progressDialog.dismiss();
                    a.render();
                    new AlertDialog.Builder(a).setTitle(L10n.t("코덱 가져오기 결과"))
                            .setMessage(outcome.summary())
                            .setPositiveButton(L10n.t("확인"), null).show();
                });
            } catch (Exception error) {
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed()) {
                        return;
                    }
                    progressDialog.dismiss();
                    new AlertDialog.Builder(a).setTitle(L10n.t("코덱 가져오기 실패"))
                            .setMessage(error.getMessage())
                            .setPositiveButton(L10n.t("확인"), null).show();
                });
            }
        }, "nas-codec-import");
        worker.start();
    }
}
