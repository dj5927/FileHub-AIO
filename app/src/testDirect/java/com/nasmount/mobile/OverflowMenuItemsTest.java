package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OverflowMenuItemsTest {
    @Test public void overflowMenuHasExactlyFiveRequestedItemsInOrder() {
        assertArrayEquals(new String[] {
                "설정",
                "즐겨찾기 보기",
                "원격 저장소 보기",
                "저장소 접근 권한",
                "업데이트 확인"
        }, OverflowMenuItems.labels());
        assertEquals(5, OverflowMenuItems.labels().length);
        assertEquals(4, OverflowMenuItems.labelsForEdition(false).length);
        assertEquals(0, OverflowMenuItems.SETTINGS);
        assertEquals(1, OverflowMenuItems.FAVORITES);
        assertEquals(2, OverflowMenuItems.REMOTES);
        assertEquals(3, OverflowMenuItems.STORAGE_PERMISSION);
        assertEquals(4, OverflowMenuItems.UPDATE);
    }
}
