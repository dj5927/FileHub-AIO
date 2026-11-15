package com.nasmount.mobile;

/**
 * Width-class detection without a vendor-specific device-name check.
 * An unfolded Fold or tablet qualifies, but a rotated narrow handset does not.
 */
final class FoldPlayerLayoutPolicy {
    private FoldPlayerLayoutPolicy() {}

    static boolean isExpanded(int smallestDp, int widthDp, int heightDp) {
        // A Fold may be unfolded yet running in a narrow multi-window pane.
        // In that case honor the *app window* width, not just device size.
        return widthDp >= 600 &&
                (smallestDp >= 540 || heightDp >= 550);
    }

    static int fileGridColumns(boolean expanded, int widthDp) {
        if (!expanded) return 3;
        return widthDp >= 1100 ? 6 : widthDp >= 820 ? 5 : 4;
    }

    static int contentWidthDp(boolean expanded, int widthDp) {
        if (!expanded) return widthDp;
        return Math.min(1080, Math.max(320, widthDp - 48));
    }

    static int drawerWidthDp(boolean expanded, int widthDp) {
        return expanded ? Math.min(360, widthDp - 40) :
                Math.round(widthDp * 0.82f);
    }

    static int homeTileDp(boolean expanded) {
        return expanded ? 105 : 83;
    }

    static int railControlDp(boolean expanded, int heightDp) {
        return expanded && heightDp >= 500 ? 55 : 48;
    }
}
