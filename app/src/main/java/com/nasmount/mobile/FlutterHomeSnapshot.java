package com.nasmount.mobile;

import android.content.Context;
import android.os.Environment;
import java.util.*;

/** Small presentation snapshot; no filesystem traversal, network requests or credentials. */
final class FlutterHomeSnapshot {
    private FlutterHomeSnapshot() {}
    static Map<String,Object> create(Context c, String route, boolean hasSd,
                                     boolean favoritesExpanded, boolean networkExpanded) {
        boolean full = EditionMonetization.full(c);
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("route",route);
        data.put("language",L10n.language());
        data.put("languageIndex",L10n.selectedIndex(c));
        data.put("themeIndex",AppearanceSettings.mode(c));
        data.put("dark",AppearanceSettings.isDark(c));
        data.put("direct",BuildConfig.DIRECT_EDITION);
        data.put("full",full);
        data.put("version",BuildConfig.VERSION_NAME);
        data.put("languages",Arrays.asList(L10n.choices()));
        data.put("themes",Arrays.asList(L10n.labels(AppearanceSettings.LABELS)));
        data.put("hasSd",hasSd);
        data.put("favoritesExpanded",favoritesExpanded);
        data.put("networkExpanded",networkExpanded);
        long free = -1;
        try { free = Environment.getExternalStorageDirectory().getFreeSpace(); }
        catch (Exception ignored) {}
        data.put("freeBytes",free);
        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("home",L10n.t("홈"));
        labels.put("storageHeading",L10n.t("저장소"));
        labels.put("storage",L10n.t("메인 저장소"));
        labels.put("sd",L10n.t("SD 저장소"));
        labels.put("downloads",L10n.t("다운로드"));
        labels.put("images",L10n.t("이미지"));
        labels.put("audio",L10n.t("오디오"));
        labels.put("videos",L10n.t("동영상"));
        labels.put("videoShelf",L10n.t("동영상"));
        labels.put("comicShelf",L10n.t("만화책"));
        labels.put("textShelf",L10n.t("소설책"));
        labels.put("features",L10n.t("추가기능"));
        labels.put("connections",L10n.t("원격 저장소"));
        labels.put("network",L10n.t("네트워크 폴더"));
        labels.put("networkManage",L10n.t("연결 추가 · 관리"));
        labels.put("favorites",L10n.t("즐겨찾기"));
        labels.put("favoriteEmpty",L10n.t("등록된 즐겨찾기가 없습니다."));
        labels.put("favoriteError",L10n.t("즐겨찾기를 확인할 수 없습니다."));
        labels.put("premiumUpgrade",L10n.t("프리미엄 업그레이드"));
        labels.put("settings",L10n.t("설정"));
        labels.put("manage",L10n.t("설정 수정"));
        labels.put("upgrade",L10n.t("풀버전으로 업그레이드"));
        labels.put("refresh",L10n.t("새로고침"));
        labels.put("error",L10n.t("설정을 확인해 주세요"));
        labels.put("profileError",L10n.t("네트워크 연결을 확인할 수 없습니다."));
        labels.put("nativeDrawer",L10n.t("기존 탐색 메뉴"));
        labels.put("available",L10n.t("남은 용량"));
        labels.put("close",L10n.t("닫기"));
        labels.put("reorder",L10n.t("순서 변경"));
        labels.put("readOnly",L10n.t("읽기 전용"));
        labels.put("add",L10n.t("원격 저장소 추가"));
        labels.put("backup",L10n.t("설정 파일 내보내기 · 가져오기"));
        labels.put("limit",L10n.t("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));
        labels.put("locked",L10n.t("풀버전 필요"));
        labels.put("empty",L10n.t("연결을 추가하면 여기에 표시돼요."));
        labels.put("nativeList",L10n.t("기존 연결 관리"));
        data.put("labels",labels);
        data.put("profiles",Collections.emptyList());
        data.put("favorites",Collections.emptyList());
        data.put("profileError",false);
        data.put("favoriteError",false);
        SecureStore store = new SecureStore(c);
        try {
            // Use the same free-edition policy as the actual native open action.
            boolean connections=FlutterUiContract.CONNECTIONS.equals(route);
            List<Models.RemoteProfile> profiles=connections?store.supportedRemotes():store.activeRemotes();
            data.put("profiles",FlutterUiContract.summaries(profiles,connections?full:true));
        } catch (Exception e) { data.put("profileError",true); }
        if (FlutterUiContract.DRAWER.equals(route)||FlutterUiContract.FAVORITES.equals(route)) {
            try { data.put("favorites",FlutterUiContract.favoriteSummaries(store.activeFavorites())); }
            catch (Exception e) { data.put("favoriteError",true); }
        }
        return data;
    }
}
