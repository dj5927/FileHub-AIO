package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppearanceSettingsTest {
    @Test public void preservesLightDefault() {
        assertEquals(AppearanceSettings.LIGHT, AppearanceSettings.normalized(-1));
        assertEquals(AppearanceSettings.LIGHT, AppearanceSettings.normalized(7));
    }
    @Test public void explicitModesOverrideSystem() {
        assertFalse(AppearanceSettings.isDarkFor(AppearanceSettings.LIGHT, true));
        assertTrue(AppearanceSettings.isDarkFor(AppearanceSettings.DARK, false));
    }
    @Test public void systemModeTracksDevice() {
        assertFalse(AppearanceSettings.isDarkFor(AppearanceSettings.SYSTEM, false));
        assertTrue(AppearanceSettings.isDarkFor(AppearanceSettings.SYSTEM, true));
    }
}
