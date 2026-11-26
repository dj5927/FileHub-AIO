package com.nasmount.mobile;

/** Single source of truth for the top-right overflow menu. */
final class OverflowMenuItems {
    static final int SETTINGS = 0;
    static final int FAVORITES = 1;
    static final int REMOTES = 2;
    static final int STORAGE_PERMISSION = 3;
    static final int UPDATE = 4;

    private OverflowMenuItems() {}

    static String[] labelsForEdition(boolean direct) {
        String[] labels=labels();
        return direct ? labels : java.util.Arrays.copyOf(labels,4);
    }

    static String[] labels() {
        return new String[] {
                "설정",
                "즐겨찾기 보기",
                "원격 저장소 보기",
                "저장소 접근 권한",
                "업데이트 확인"
        };
    }
}
