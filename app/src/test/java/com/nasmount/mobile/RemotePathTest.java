package com.nasmount.mobile;

import static org.junit.Assert.*;
import org.junit.Test;

/** Pure, offline path boundary tests; no network account is accessed. */
public final class RemotePathTest {
    @Test public void rootAndEmpty() {
        assertEquals("/", RemoteProvider.relativePath(null));
        assertEquals("/", RemoteProvider.relativePath(""));
        assertEquals("/", RemoteProvider.relativePath("/"));
    }
    @Test public void unicodeAndNestedPaths() {
        assertEquals("/개인자료/완료", RemoteProvider.relativePath(
                "//개인자료//완료/"));
        assertEquals("/개인자료/완료", RemoteProvider.child("/개인자료", "완료"));
        assertEquals("/개인자료", RemoteProvider.parent("/개인자료/완료"));
        assertEquals("/", RemoteProvider.parent("/개인자료"));
    }
    @Test public void rejectedTraversal() {
        for (String path : new String[]{"../secret", "/a/../b", "/../", "/a/../"}) {
            try {
                RemoteProvider.relativePath(path);
                fail("Path traversal accepted: " + path);
            } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void rejectedInvalidNames() {
        for (String name : new String[]{"", ".", "..", "../secret", "sub/child",
                "a\\b", "file\0secret"}) {
            try {
                RemoteProvider.child("/safe", name);
                fail("Unsafe filename accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void notConfusingSimilarPaths() {
        assertEquals("/abc", RemoteProvider.relativePath("/abc"));
        assertEquals("/abc2", RemoteProvider.relativePath("/abc2"));
        assertEquals("/abc", RemoteProvider.parent("/abc/def"));
    }
}
