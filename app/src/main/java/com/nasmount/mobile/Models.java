package com.nasmount.mobile;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;

final class Models {
    private Models() {}

    static final class RemoteProfile {
        String id = java.util.UUID.randomUUID().toString();
        String name = "";
        String protocol = "SMB";
        String host = "";
        int port = 445;
        String root = "";
        String username = "";
        String password = "";
        String fingerprint = "";
        boolean ssl = true;
        // Shared FileHub PC/Android connection options. Older Android
        // installations default to their previously supported plain FTP.
        boolean ftpExplicitTls;
        boolean ftpImplicitTls;
        boolean ftpPreferPasv = true;
        boolean ftpTls12Only;
        String ftpCharset = "UTF-8";
        String sftpCharset = "UTF-8";
        boolean readOnly;
        boolean useLockBridge;
        boolean useNativeWebDavLocks;
        boolean officeReadTuning;
        boolean autoConnect;
        String windowsLetter = "";
        // Optional manufacturer-specific File Station API. Never inferred from a
        // generic SMB/WebDAV server or enabled until API verification succeeds.
        String nasVendor = "";
        String nasApiBase = "";
        String nasApiUser = "";
        String nasApiPassword = "";
        // DSM trusted-device ID may be stored encrypted, but an OTP must never persist.
        String nasDeviceId = "";
        transient String nasOtpCode = "";
        transient boolean nasRememberDevice;
        String nasShareRoot = "";
        // Synology may allow many independent File Station shared folders.
        // Empty for legacy installations: nasShareRoot is migrated on read.
        java.util.List<String> nasShareRoots = new java.util.ArrayList<>();
        String nasPublicHost = "";
        // Official QNAP CloudLink browser SmartURL; not a native relay API token.
        String qnapCloudSmartUrl = "";

        String label() {
            return name.trim().isEmpty() ? protocol + " · " + host : name.trim();
        }
        String summary() {
            return protocol + "  ·  " + host + (port > 0 ? ":" + port : "");
        }
    }

    static final class Favorite {
        String kind; // local, saf, remote
        String title; // original folder name, preserved from existing installs
        String path;
        String remoteId;
        String alias; // user-visible label only; never part of the folder path
        Favorite(String kind, String title, String path, String remoteId) {
            this(kind, title, path, remoteId, "");
        }
        Favorite(String kind, String title, String path, String remoteId,
                 String alias) {
            this.kind = kind; this.title = title; this.path = path;
            this.remoteId = remoteId;
            this.alias = alias == null ? "" : alias;
        }
        String displayTitle() {
            return alias == null || alias.trim().isEmpty() ? title : alias;
        }
        boolean hasCustomTitle() {
            return alias != null && !alias.isEmpty() && !alias.equals(title);
        }
        String key() {
            return kind + "|" + (remoteId == null ? "" : remoteId) + "|" + path;
        }
    }

    static final class Entry {
        String name;
        String path;
        String remoteId = "";
        boolean directory;
        // SFTP symbolic links may point to directories. Keep the link
        // identity even when the target is a navigable directory, so bulk
        // operations can never accidentally delete the target recursively.
        boolean symbolicLink;
        boolean linkTargetUnavailable;
        long size = -1;
        long modified;
        File file;
        DocumentFile document;
        Uri uri;
        Entry(String name, String path, boolean directory) {
            this.name = name; this.path = path; this.directory = directory;
        }
    }
}
