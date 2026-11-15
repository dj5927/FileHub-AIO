package com.nasmount.mobile;

import org.junit.Test;

import static org.junit.Assert.*;

public class AssignedFolderBackBoundaryTest {
    @Test public void localNestedFolderReturnsToAssignedRootBeforeHome() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "local", "", "/storage/emulated/0/Movies");
        assertTrue(boundary.contains("local", "", "/storage/emulated/0/Movies/series/ep01"));
        assertFalse(boundary.atRoot("local", "", "/storage/emulated/0/Movies/series"));
        assertTrue(boundary.atRoot("local", "", "/storage/emulated/0/Movies"));
        assertFalse(boundary.contains("local", "", "/storage/emulated/0"));
    }

    @Test public void remoteFoldersKeepProviderAndAccountBoundaries() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "remote", "nasA", "/01.개인자료/만화책");
        assertTrue(boundary.atRoot("remote", "nasA", "/01.개인자료/만화책"));
        assertTrue(boundary.contains("remote", "nasA", "/01.개인자료/만화책/권1"));
        assertFalse(boundary.contains("remote", "nasB", "/01.개인자료/만화책"));
        assertFalse(boundary.contains("remote", "nasA", "/01.개인자료/만화책2"));
    }

    @Test public void safRootsCannotBeMixedAcrossAccessTrees() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "saf", "content://a/tree/123", "/Books/Novels");
        assertTrue(boundary.atRoot("saf", "content://a/tree/123", "/Books/Novels/"));
        assertTrue(boundary.contains("saf", "content://a/tree/123", "/Books/Novels/Book1"));
        assertFalse(boundary.contains("saf", "content://a/tree/999", "/Books/Novels"));
        assertFalse(boundary.contains("saf", "content://a/tree/123", "/Books"));
    }

    @Test public void storageRootBoundaryWorksForTopLevelAssignments() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "remote", "server", "/");
        assertTrue(boundary.atRoot("remote", "server", "/"));
        assertTrue(boundary.contains("remote", "server", "/Movies/2026"));
        assertFalse(boundary.atRoot("remote", "server", "/Movies"));
    }

    @Test public void breadcrumbCannotEscapeAssignedMovieRoot() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "remote", "nasId", "/2.영화/2_외국영화");
        assertEquals("/2.영화/2_외국영화",
                boundary.limitBreadcrumb("remote", "nasId", "/2.영화"));
        assertEquals("/2.영화/2_외국영화",
                boundary.limitBreadcrumb("remote", "nasId", "/"));
        assertEquals("/2.영화/2_외국영화/글래디에이터",
                boundary.limitBreadcrumb("remote", "nasId",
                        "/2.영화/2_외국영화/글래디에이터"));
        assertFalse(boundary.atRoot("remote", "nasId",
                "/2.영화/2_외국영화/글래디에이터"));
        assertTrue(boundary.atRoot("remote", "nasId", "/2.영화/2_외국영화"));
    }

    @Test public void breadcrumbLimitNeverAffectsOtherAccounts() {
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "remote", "nasId", "/movie");
        assertEquals("/other", boundary.limitBreadcrumb("remote", "otherNas", "/other"));
        assertEquals("/other", boundary.limitBreadcrumb("local", "", "/other"));
    }

    @Test public void virtualShelfChildBackGoesToConfiguredParentThenHome() {
        AssignedFolderCatalog.Folder saved = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "nas", "/2.영화/4_드라마");
        AssignedFolderCatalog.Folder displayed = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "nas",
                "/2.영화/4_드라마/가족계획 6부작 완결");
        AssignedFolderCatalog.Folder selected = AssignedFolderCatalog.configuredRoot(
                java.util.Collections.singletonList(saved), displayed);
        assertEquals(saved.path, selected.path);
        AssignedFolderBackBoundary boundary = new AssignedFolderBackBoundary(
                "remote", "nas", selected.path);
        assertFalse(boundary.atRoot("remote", "nas", displayed.path));
        assertTrue(boundary.atRoot("remote", "nas", saved.path));
    }

    @Test public void nearestSavedAncestorWinsAndOtherAccountsAreUnchanged() {
        AssignedFolderCatalog.Folder parent = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "nas", "/2.영화");
        AssignedFolderCatalog.Folder inner = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "nas", "/2.영화/4_드라마");
        AssignedFolderCatalog.Folder displayed = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "nas", "/2.영화/4_드라마/가족계획");
        assertEquals(inner.path, AssignedFolderCatalog.configuredRoot(
                java.util.Arrays.asList(parent, inner), displayed).path);
        AssignedFolderCatalog.Folder other = new AssignedFolderCatalog.Folder(
                AssignedFolderCatalog.VIDEO, "remote", "other", displayed.path);
        assertEquals(other.path, AssignedFolderCatalog.configuredRoot(
                java.util.Arrays.asList(parent, inner), other).path);
    }

    @Test public void parentBreadcrumbBecomesNewBackBoundary() {
        AssignedFolderBackBoundary before = new AssignedFolderBackBoundary(
                "remote", "nas", "/2.영화/4_드라마");
        assertFalse(before.contains("remote", "nas", "/2.영화"));
        AssignedFolderBackBoundary after = new AssignedFolderBackBoundary(
                "remote", "nas", "/2.영화");
        assertTrue(after.atRoot("remote", "nas", "/2.영화"));
        assertFalse(after.atRoot("remote", "nas", "/2.영화/4_드라마"));
    }
}
