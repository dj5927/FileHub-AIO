package com.nasmount.mobile;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class FavoritePersistenceTest {
    @Test public void legacyFavoritesReadWithoutAlias() throws Exception {
        String legacy = "[{\"kind\":\"remote\",\"title\":\"02영화\"," +
                "\"path\":\"/Movies/02영화\",\"remoteId\":\"abc\"}]";
        Models.Favorite f = SecureStore.decodeFavorites(legacy).get(0);
        assertEquals("02영화", f.displayTitle());
        assertFalse(f.hasCustomTitle());
        assertEquals("remote|abc|/Movies/02영화", f.key());
    }
    @Test public void aliasSurvivesPersistenceWithoutChangingSourceFolder()
            throws Exception {
        Models.Favorite favorite = new Models.Favorite(
                "saf", "02영화", "/Movies/02영화", "content://card/tree/ABCD");
        favorite.alias = FavoriteNamePolicy.normalize("영화모음집", favorite.title);
        List<Models.Favorite> saved = SecureStore.decodeFavorites(
                SecureStore.encodeFavorites(List.of(favorite)));
        assertEquals("영화모음집", saved.get(0).displayTitle());
        assertEquals("02영화", saved.get(0).title);
        assertEquals("/Movies/02영화", saved.get(0).path);
        assertEquals(favorite.key(), saved.get(0).key());
        assertTrue(saved.get(0).hasCustomTitle());
    }
    @Test public void resetRestoresOriginalNameAfterSerialization() throws Exception {
        Models.Favorite favorite = new Models.Favorite(
                "local", "2.영화", "/storage/2.영화", "");
        favorite.alias = FavoriteNamePolicy.normalize("", favorite.title);
        assertEquals("2.영화", SecureStore.decodeFavorites(
                SecureStore.encodeFavorites(List.of(favorite))).get(0).displayTitle());
    }
}
