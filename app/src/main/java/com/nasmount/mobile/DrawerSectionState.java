package com.nasmount.mobile;

/**
 * Independent, persistent expand/collapse state for the left navigation menu.
 * Defaults to expanded so an existing user's entries remain visible after
 * updating from an older version. Each tap is saved immediately.
 */
final class DrawerSectionState {
    static final String PREFS_NAME = "drawer_sections";
    static final String FAVORITES = "favorites";
    static final String NETWORK = "network";

    interface Storage {
        boolean read(String key, boolean defaultValue);
        void write(String key, boolean value);
    }

    private final Storage storage;

    DrawerSectionState(Storage storage) {
        if (storage == null) throw new IllegalArgumentException("Missing drawer storage");
        this.storage = storage;
    }

    static String key(String section) {
        if (FAVORITES.equals(section)) return "favorites_expanded";
        if (NETWORK.equals(section)) return "network_expanded";
        throw new IllegalArgumentException("Unknown drawer section: " + section);
    }

    boolean expanded(String section) {
        return storage.read(key(section), true);
    }

    boolean toggle(String section) {
        boolean next = !expanded(section);
        storage.write(key(section), next);
        return next;
    }
}
