package com.nasmount.mobile;

/**
 * Pure decision rules for SD-card permission routing.
 * SAF cannot grant a reliable SD-volume root on Android 11+, while
 * MANAGE_EXTERNAL_STORAGE may provide direct file-path access.
 */
final class SdAccessPolicy {
    private SdAccessPolicy() {}

    static boolean isMountedSd(boolean removable, boolean primary, String state) {
        return removable && !primary &&
                ("mounted".equals(state) || "mounted_ro".equals(state));
    }

    static boolean useDirectRoot(int sdk, boolean allFilesPermission,
                                 boolean readableDirectory) {
        return sdk >= 30 && allFilesPermission && readableDirectory;
    }

    static boolean validSdDocumentId(String authority, String treeDocumentId,
                                     String sdVolumeUuid) {
        if (treeDocumentId == null || treeDocumentId.isEmpty()) return false;
        if (authority == null || !authority.endsWith(".externalstorage.documents"))
            return false;
        int split = treeDocumentId.indexOf(':');
        if (split < 1) return false;
        String id = treeDocumentId.substring(0, split);
        if ("primary".equalsIgnoreCase(id) || "home".equalsIgnoreCase(id))
            return false;
        if (sdVolumeUuid != null && !sdVolumeUuid.trim().isEmpty())
            return id.equalsIgnoreCase(sdVolumeUuid);
        return true;
    }

    static String rootFromAppFilesPath(String path) {
        if (path == null) return null;
        String normalized = path.replace('\\', '/');
        int androidData = normalized.indexOf("/Android/data/");
        if (androidData <= 0 || !normalized.startsWith("/storage/"))
            return null;
        return normalized.substring(0, androidData);
    }
}
