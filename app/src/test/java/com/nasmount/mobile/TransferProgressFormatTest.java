package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransferProgressFormatTest {
    @Test public void percentNeverClaimsCompletionBeforeReturn() {
        assertEquals(0, TransferProgressFormat.percent(0, 100, false));
        assertEquals(50, TransferProgressFormat.percent(50, 100, false));
        assertEquals(99, TransferProgressFormat.percent(100, 100, false));
        assertEquals(100, TransferProgressFormat.percent(100, 100, true));
    }

    @Test public void unknownSizeUsesIndeterminateUi() {
        assertEquals(-1, TransferProgressFormat.percent(3000, -1, false));
        assertEquals(-1, TransferProgressFormat.percent(0, 0, false));
        assertTrue(TransferProgressFormat.amount(400, -1).contains("전체 용량 확인 불가"));
    }

    @Test public void sizeHumanReadable() {
        assertEquals("0 B", TransferProgressFormat.bytes(0));
        assertEquals("512 B", TransferProgressFormat.bytes(512));
        assertEquals("1.5 MB", TransferProgressFormat.bytes(1572864));
    }
}
