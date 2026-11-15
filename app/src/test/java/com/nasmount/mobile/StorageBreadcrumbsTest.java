package com.nasmount.mobile;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class StorageBreadcrumbsTest {
    @Test public void internalStorageShowsAllClickableAncestors() {
        List<RemoteBreadcrumbs.Step> items=StorageBreadcrumbs.steps(
                "/storage/emulated/0",
                "/storage/emulated/0/1.예능/어서와 한국은 처음이지 시즌2");
        assertEquals(3,items.size());
        assertEquals("⌂",items.get(0).label);
        assertEquals("/storage/emulated/0",items.get(0).path);
        assertEquals("1.예능",items.get(1).label);
        assertEquals("/storage/emulated/0/1.예능",items.get(1).path);
        assertEquals("어서와 한국은 처음이지 시즌2",items.get(2).label);
        assertEquals("/storage/emulated/0/1.예능/어서와 한국은 처음이지 시즌2",
                items.get(2).path);
    }
    @Test public void rootHasOnlyRootChip() {
        assertEquals(1, StorageBreadcrumbs.steps(
                "/storage/emulated/0", "/storage/emulated/0").size());
    }
    @Test public void sdCardUsesVolumeRootNotEmulatedStorage() {
        List<RemoteBreadcrumbs.Step> items=StorageBreadcrumbs.steps(
                "/storage/9C33-6BBD", "/storage/9C33-6BBD/Movies/Drama");
        assertEquals("/storage/9C33-6BBD",items.get(0).path);
        assertEquals("/storage/9C33-6BBD/Movies",items.get(1).path);
        assertEquals("Drama",items.get(2).label);
    }
    @Test public void similarlyPrefixedFoldersAreNotInsideRoot() {
        assertFalse(StorageBreadcrumbs.withinRoot("/storage/emulated/0",
                "/storage/emulated/01/secrets"));
        assertFalse(StorageBreadcrumbs.withinRoot("/storage/9C33-6BBD",
                "/storage/9C33-6BBD2/private"));
        assertFalse(StorageBreadcrumbs.withinRoot("/storage/emulated/0",
                "/storage/emulated/0/../other"));
    }
    @Test(expected=IllegalArgumentException.class)
    public void rejectsNavigationOutsideVolume() {
        StorageBreadcrumbs.steps("/storage/emulated/0",
                "/storage/emulated/0/../../outside");
    }
    @Test public void safBreadcrumbsRemainScopedToSelectedTree() {
        List<RemoteBreadcrumbs.Step> items=RemoteBreadcrumbs.steps(
                "/Movies/Korean/2026");
        assertEquals("/",items.get(0).path);
        assertEquals("/Movies",items.get(1).path);
        assertEquals("/Movies/Korean",items.get(2).path);
        assertEquals("/Movies/Korean/2026",items.get(3).path);
    }
}
