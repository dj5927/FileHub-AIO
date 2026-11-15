package com.nasmount.mobile;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class FavoriteOrderPolicyTest {
    private static Models.Favorite favorite(String folder) {
        return new Models.Favorite("remote",folder,"/video/"+folder,"nas1");
    }

    private static List<String> keys(List<Models.Favorite> favorites) {
        List<String> result=new ArrayList<>();
        for(Models.Favorite item:favorites)result.add(item.key());
        return result;
    }

    @Test public void movingFirstToLastAndLastToFirstChangesOnlyOrder() {
        List<String> initial=Arrays.asList("film","shorts","book","comic");
        assertEquals(Arrays.asList("shorts","book","comic","film"),
                FavoriteOrderPolicy.move(initial,0,3));
        assertEquals(Arrays.asList("comic","film","shorts","book"),
                FavoriteOrderPolicy.move(initial,3,0));
        assertEquals(Arrays.asList("film","shorts","book","comic"),initial);
    }

    @Test public void hiddenEntriesStayAtTheirOriginalIndices() {
        Models.Favorite film=favorite("film");
        Models.Favorite oldGoogle=new Models.Favorite("remote","Google", "/old", "retired");
        Models.Favorite books=favorite("books");
        Models.Favorite comic=favorite("comic");
        List<Models.Favorite> saved=Arrays.asList(film,oldGoogle,books,comic);
        List<String> current=Arrays.asList(film.key(),books.key(),comic.key());
        List<String> desired=Arrays.asList(comic.key(),film.key(),books.key());
        List<Models.Favorite> reordered=FavoriteOrderPolicy.reorderVisible(
                saved,current,desired);
        assertSame(comic,reordered.get(0));
        assertSame(oldGoogle,reordered.get(1));
        assertSame(film,reordered.get(2));
        assertSame(books,reordered.get(3));
        assertSame(film,saved.get(0));
    }

    @Test public void aliasesPathsAndIdsAreNotChanged() {
        Models.Favorite film=favorite("film");
        film.alias="영화모음";
        Models.Favorite shortVideos=favorite("shorts");
        List<Models.Favorite> saved=Arrays.asList(film,shortVideos);
        List<Models.Favorite> moved=FavoriteOrderPolicy.reorderVisible(saved,
                keys(saved),Arrays.asList(shortVideos.key(),film.key()));
        assertEquals("영화모음",moved.get(1).alias);
        assertEquals("/video/film",moved.get(1).path);
        assertEquals("nas1",moved.get(1).remoteId);
    }

    @Test public void invalidReorderCannotCreateOrRemoveFavorites() {
        Models.Favorite film=favorite("film");
        Models.Favorite books=favorite("books");
        List<Models.Favorite> saved=Arrays.asList(film,books);
        List<String> before=keys(saved);
        assertThrows(IllegalArgumentException.class,()->FavoriteOrderPolicy.reorderVisible(
                saved,before,Arrays.asList(film.key(),film.key())));
        assertThrows(IllegalArgumentException.class,()->FavoriteOrderPolicy.reorderVisible(
                saved,before,Arrays.asList(books.key(),"not-a-favorite")));
        assertThrows(IllegalArgumentException.class,()->FavoriteOrderPolicy.move(before,-1,0));
        assertThrows(IllegalArgumentException.class,()->FavoriteOrderPolicy.move(before,0,2));
    }
}
