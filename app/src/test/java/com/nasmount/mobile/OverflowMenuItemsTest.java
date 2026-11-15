package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OverflowMenuItemsTest {
    @Test public void overflowMenuHasExactlyFourRequestedItemsInOrder() {
        assertArrayEquals(new String[] {
                "즐겨찾기 보기",
                "원격 저장소 보기",
                "저장소 접근 권한",
                "업데이트 확인"
        }, OverflowMenuItems.labels());
        assertEquals(4, OverflowMenuItems.labels().length);
        assertEquals(0, OverflowMenuItems.FAVORITES);
        assertEquals(1, OverflowMenuItems.REMOTES);
        assertEquals(2, OverflowMenuItems.STORAGE_PERMISSION);
        assertEquals(3, OverflowMenuItems.UPDATE);
    }
}
