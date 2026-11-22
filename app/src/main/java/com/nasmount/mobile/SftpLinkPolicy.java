package com.nasmount.mobile;

import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.FileMode;

/**
 * Distinguishes a POSIX directory link from a real directory.
 * SFTP READDIR provides LSTAT-like attributes, so a symlink must be followed
 * once with STAT to learn the target's file/directory type for UI navigation.
 * Preserve its identity for safe transfer and deletion semantics.
 */
final class SftpLinkPolicy {
    private SftpLinkPolicy() {}

    static Models.Entry entry(String name, String path,
                              FileAttributes listed, FileAttributes target) {
        boolean link = listed.getType() == FileMode.Type.SYMLINK;
        // target is only non-null for a link after a successful SFTP STAT.
        FileAttributes displayed = link && target != null ? target : listed;
        Models.Entry result = new Models.Entry(name, path,
                displayed.getType() == FileMode.Type.DIRECTORY);
        result.symbolicLink = link;
        result.linkTargetUnavailable = link && target == null;
        result.size = displayed.getSize();
        result.modified = displayed.getMtime() * 1000L;
        return result;
    }

    static boolean shouldDescendForDelete(boolean directory, boolean symbolicLink) {
        // A symlink can be a directory for *navigation*, but unlinking
        // must NEVER recurse into the target directory.
        return directory && !symbolicLink;
    }
}
