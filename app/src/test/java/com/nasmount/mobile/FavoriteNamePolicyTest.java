package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class FavoriteNamePolicyTest {
    @Test public void aliasKeepsOriginalFolderAndIdentity() {
        Models.Favorite f = new Models.Favorite(
                "remote", "02영화", "/Videos/02영화", "remote-123");
        String key = f.key();
        f.alias = FavoriteNamePolicy.normalize("영화모음집", f.title);
        assertEquals("영화모음집", f.displayTitle());
        assertEquals("02영화", f.title);
        assertEquals("/Videos/02영화", f.path);
        assertEquals(key, f.key());
        assertTrue(f.hasCustomTitle());
    }
    @Test public void emptyOrOriginalRestoresDefault() {
        assertEquals("", FavoriteNamePolicy.normalize("  ", "02영화"));
        assertEquals("", FavoriteNamePolicy.normalize("02영화", "02영화"));
        Models.Favorite legacy = new Models.Favorite("local", "2.영화", "/mnt/2.영화", "");
        assertEquals("2.영화", legacy.displayTitle());
        assertFalse(legacy.hasCustomTitle());
    }
    @Test public void validKoreanAndSpacesRemainAvailable() {
        assertEquals("영화 모음집", FavoriteNamePolicy.normalize(" 영화 모음집 ", "02영화"));
        assertEquals("영화/드라마", FavoriteNamePolicy.normalize("영화/드라마", "02영화"));
    }
    @Test public void invalidControlCharactersAndLongNamesBlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> FavoriteNamePolicy.normalize("영화\n목록", "02영화"));
        assertThrows(IllegalArgumentException.class,
                () -> FavoriteNamePolicy.normalize("x".repeat(81), "02영화"));
    }
}
