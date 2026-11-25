package com.nasmount.mobile;

/** Single source of truth for the top-right overflow menu. */
final class OverflowMenuItems {
    static final int FAVORITES = 0;
    static final int REMOTES = 1;
    static final int STORAGE_PERMISSION = 2;
    static final int UPDATE = 3;

    private OverflowMenuItems() {}

    static String[] labelsForEdition(boolean direct) {
        String[] labels=labels();
        return direct ? labels : java.util.Arrays.copyOf(labels,3);
    }

    static String[] labels() {
        return new String[] {
                "즐겨찾기 보기",
                "원격 저장소 보기",
                "저장소 접근 권한",
                "업데이트 확인"
        };
    }
}
