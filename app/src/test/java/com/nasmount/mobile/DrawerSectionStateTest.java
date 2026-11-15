package com.nasmount.mobile;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class DrawerSectionStateTest {
    private static final class SavedPreferences implements DrawerSectionState.Storage {
        private final Map<String, Boolean> values = new HashMap<>();

        @Override public boolean read(String key, boolean fallback) {
            return values.getOrDefault(key, fallback);
        }

        @Override public void write(String key, boolean value) {
            values.put(key, value);
        }
    }

    @Test public void newUsersSeeExpandedSections() {
        DrawerSectionState drawer = new DrawerSectionState(new SavedPreferences());
        assertTrue(drawer.expanded(DrawerSectionState.FAVORITES));
        assertTrue(drawer.expanded(DrawerSectionState.NETWORK));
    }

    @Test public void tappingFavoritesDoesNotCollapseNetwork() {
        DrawerSectionState drawer = new DrawerSectionState(new SavedPreferences());
        assertFalse(drawer.toggle(DrawerSectionState.FAVORITES));
        assertFalse(drawer.expanded(DrawerSectionState.FAVORITES));
        assertTrue(drawer.expanded(DrawerSectionState.NETWORK));
    }

    @Test public void tappingNetworkDoesNotExpandFavorites() {
        DrawerSectionState drawer = new DrawerSectionState(new SavedPreferences());
        drawer.toggle(DrawerSectionState.FAVORITES);
        assertFalse(drawer.toggle(DrawerSectionState.NETWORK));
        assertFalse(drawer.expanded(DrawerSectionState.FAVORITES));
        assertFalse(drawer.expanded(DrawerSectionState.NETWORK));
    }

    @Test public void expansionRestoresAfterAppRestartUsingSameStorage() {
        SavedPreferences prefs = new SavedPreferences();
        DrawerSectionState first = new DrawerSectionState(prefs);
        first.toggle(DrawerSectionState.FAVORITES);
        DrawerSectionState restarted = new DrawerSectionState(prefs);
        assertFalse(restarted.expanded(DrawerSectionState.FAVORITES));
        assertTrue(restarted.expanded(DrawerSectionState.NETWORK));
        restarted.toggle(DrawerSectionState.NETWORK);
        DrawerSectionState reopened = new DrawerSectionState(prefs);
        assertFalse(reopened.expanded(DrawerSectionState.FAVORITES));
        assertFalse(reopened.expanded(DrawerSectionState.NETWORK));
    }

    @Test public void canExpandPreviouslyCollapsedSection() {
        DrawerSectionState drawer = new DrawerSectionState(new SavedPreferences());
        assertFalse(drawer.toggle(DrawerSectionState.NETWORK));
        assertTrue(drawer.toggle(DrawerSectionState.NETWORK));
        assertTrue(drawer.expanded(DrawerSectionState.NETWORK));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cannotAccidentallyPersistUnknownSection() {
        new DrawerSectionState(new SavedPreferences()).toggle("settings");
    }
}
