package com.nasmount.mobile;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;
import org.junit.Test;
import static org.junit.Assert.*;

public class SftpLinkPolicyTest {
    private static FileAttributes attrs(FileMode.Type type, long size) {
        return new FileAttributes.Builder().withType(type)
                .withPermissions(type == FileMode.Type.DIRECTORY ? 0755 : 0644)
                .withSize(size).withAtimeMtime(10, 20).build();
    }

    @Test public void steamDeckSteamAndRootSymlinksBecomeNavigableDirectories() {
        for (String name : new String[]{"steam", "root", "bin64"}) {
            Models.Entry entry = SftpLinkPolicy.entry(name,
                    "/home/deck/.steam/" + name,
                    attrs(FileMode.Type.SYMLINK, 30),
                    attrs(FileMode.Type.DIRECTORY, 4096));
            assertTrue(entry.directory);
            assertTrue(entry.symbolicLink);
            assertFalse(entry.linkTargetUnavailable);
            assertEquals("/home/deck/.steam/" + name, entry.path);
            assertEquals(4096, entry.size);
            assertEquals(20_000L, entry.modified);
            assertFalse(SftpLinkPolicy.shouldDescendForDelete(
                    entry.directory, entry.symbolicLink));
            FileTransferEngine.Item selected = FileTransferEngine.remote(
                    new Models.RemoteProfile(), entry);
            assertTrue(selected.directory);
            assertTrue(selected.symbolicLink);
            assertThrows(java.io.IOException.class,
                    () -> FileTransferEngine.children(selected));
        }
    }

    @Test public void regularFolderRemainsNormalAndDeletableAsFolder() {
        Models.Entry entry = SftpLinkPolicy.entry("Downloads",
                "/home/deck/Downloads",
                attrs(FileMode.Type.DIRECTORY, 4096), null);
        assertTrue(entry.directory);
        assertFalse(entry.symbolicLink);
        assertFalse(entry.linkTargetUnavailable);
        assertTrue(SftpLinkPolicy.shouldDescendForDelete(
                entry.directory, entry.symbolicLink));
    }

    @Test public void symlinkToFileDoesNotBecomeFolder() {
        Models.Entry entry = SftpLinkPolicy.entry("steam.pid",
                "/home/deck/.steam/steam.pid",
                attrs(FileMode.Type.SYMLINK, 10),
                attrs(FileMode.Type.REGULAR, 6));
        assertFalse(entry.directory);
        assertTrue(entry.symbolicLink);
        assertFalse(entry.linkTargetUnavailable);
        assertEquals(6, entry.size);
        assertFalse(SftpLinkPolicy.shouldDescendForDelete(
                entry.directory, entry.symbolicLink));
    }

    @Test public void inaccessibleOrDanglingSymlinkDoesNotCrashWholeListing() {
        Models.Entry entry = SftpLinkPolicy.entry("missing",
                "/home/deck/.steam/missing",
                attrs(FileMode.Type.SYMLINK, 29), null);
        assertFalse(entry.directory);
        assertTrue(entry.symbolicLink);
        assertTrue(entry.linkTargetUnavailable);
        assertFalse(SftpLinkPolicy.shouldDescendForDelete(
                entry.directory, entry.symbolicLink));
    }

    @Test public void cycleProtectionDoesNotTraverseLinkedFoldersForBulkCopyOrDelete() {
        Models.Entry loop = SftpLinkPolicy.entry("cycle", "/home/deck/loop",
                attrs(FileMode.Type.SYMLINK, 2),
                attrs(FileMode.Type.DIRECTORY, 4096));
        FileTransferEngine.Item item = FileTransferEngine.remote(
                new Models.RemoteProfile(), loop);
        assertThrows(java.io.IOException.class,
                () -> FileTransferEngine.children(item));
        assertFalse(SftpLinkPolicy.shouldDescendForDelete(
                item.directory, item.symbolicLink));
        assertTrue(SftpLinkPolicy.shouldDescendForDelete(true, false));
    }
}
