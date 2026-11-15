package com.nasmount.mobile;

/**
 * The entry folder of a video/comic/novel shortcut is a navigation boundary.
 * Back from that folder goes home, not to its physical storage parent.
 * No persistent folder-role setting is modified.
 */
final class AssignedFolderBackBoundary {
    private final String screen;
    private final String owner;
    private final String root;

    AssignedFolderBackBoundary(String screen, String owner, String root) {
        this.screen = screen;
        this.owner = owner == null ? "" : owner;
        this.root = normalize(root);
    }

    boolean contains(String screen, String owner, String path) {
        if (!this.screen.equals(screen) ||
                !this.owner.equals(owner == null ? "" : owner)) return false;
        String current = normalize(path);
        return current.equals(root) || root.equals("/") ||
                current.startsWith(root + "/");
    }

    boolean atRoot(String screen, String owner, String path) {
        return contains(screen, owner, path) && root.equals(normalize(path));
    }

    String limitBreadcrumb(String screen, String owner, String requested) {
        if (!this.screen.equals(screen) ||
                !this.owner.equals(owner == null ? "" : owner)) return requested;
        return contains(screen, owner, requested) ? requested : root;
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) return "/";
        String text = value.replace('\\', '/');
        while (text.length() > 1 && text.endsWith("/"))
            text = text.substring(0, text.length() - 1);
        return text;
    }
}
