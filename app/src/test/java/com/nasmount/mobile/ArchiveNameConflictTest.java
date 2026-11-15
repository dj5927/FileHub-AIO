package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public final class ArchiveNameConflictTest {
    @Test public void missingFolderUsesOriginalName() throws Exception {
        assertEquals("사진", ArchiveWorkflows.chooseAvailableFolderName("사진",
                Collections.singleton("다른폴더")));
    }

    @Test public void existingFolderGetsFirstSuffix() throws Exception {
        assertEquals("사진 (1)", ArchiveWorkflows.chooseAvailableFolderName("사진",
                Collections.singleton("사진")));
    }

    @Test public void gapsAreFilledAndCaseInsensitiveCollisionsAreHandled()
            throws Exception {
        assertEquals("Report (2)", ArchiveWorkflows.chooseAvailableFolderName(
                "Report", new HashSet<>(Arrays.asList("REPORT", "report (1)",
                        "report (3)", "report (4)"))));
    }

    @Test public void existingFileWithSameNameAlsoRequiresSuffix() throws Exception {
        assertEquals("자료 (3)", ArchiveWorkflows.chooseAvailableFolderName("자료",
                new HashSet<>(Arrays.asList("자료", "자료 (1)", "자료 (2)"))));
    }
}
