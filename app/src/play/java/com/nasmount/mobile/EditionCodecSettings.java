package com.nasmount.mobile;
import android.net.Uri;
final class EditionCodecSettings {
    static void render(SettingsActivity a) {
        a.section(L10n.t("코덱 · 호환성"), L10n.t("Android 기기 기본 디코더만 사용합니다."));
        a.note("기기 E-AC3 디코더: " + a.detectNativeDecoder("audio/eac3") +
            "\n기기 Dolby TrueHD 디코더: " + a.detectNativeDecoder("audio/true-hd"));
        a.action(L10n.t("외부 플레이어 사용"), L10n.t("Google Play에서 별도 플레이어를 설치하고 동영상 재생 메뉴에서 선택합니다."),
                () -> ExternalApps.playerStore(a));
    }
    static void importFolder(SettingsActivity a, Uri ignored) {
        // Deliberately no executable import/loading in Play edition.
    }
}
