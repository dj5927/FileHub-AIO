package com.nasmount.mobile;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RemoteBreadcrumbsTest {
    @Test public void nestedPathGivesClickableAncestorSegments() {
        List<RemoteBreadcrumbs.Step> items =
                RemoteBreadcrumbs.steps("/01.개인자료/테스트");
        assertEquals(3, items.size());
        assertEquals("⌂", items.get(0).label);
        assertEquals("/", items.get(0).path);
        assertEquals("01.개인자료", items.get(1).label);
        assertEquals("/01.개인자료", items.get(1).path);
        assertEquals("테스트", items.get(2).label);
        assertEquals("/01.개인자료/테스트", items.get(2).path);
        for (RemoteBreadcrumbs.Step step : items)
            assertFalse(step.label.toLowerCase().contains("webdav"));
    }
    @Test public void rootOnlyShowsRoot() {
        assertEquals(1, RemoteBreadcrumbs.steps("/").size());
        assertEquals("/", RemoteBreadcrumbs.steps("").get(0).path);
    }
    @Test public void nestedLongUnicodePathDoesNotTruncateSegments() {
        List<RemoteBreadcrumbs.Step> items =
                RemoteBreadcrumbs.steps("/1.예능/드라마 작품 폴더/2026년 11월/한글 자막");
        assertEquals(5, items.size());
        assertEquals("/1.예능/드라마 작품 폴더/2026년 11월",
                items.get(3).path);
        assertEquals("한글 자막", items.get(4).label);
    }
    @Test public void traversalIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RemoteBreadcrumbs.steps("/docs/../private"));
    }
}
