package com.nasmount.mobile;

/** Files may contain both an external sidecar and one or more embedded tracks. */
final class SubtitleBadgePolicy {
    static final int EXTERNAL = 1;
    static final int EMBEDDED = 2;

    private SubtitleBadgePolicy() {}

    static int flags(boolean hasExternal, boolean hasEmbedded) {
        return (hasExternal ? EXTERNAL : 0) | (hasEmbedded ? EMBEDDED : 0);
    }
}
