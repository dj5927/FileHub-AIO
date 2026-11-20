package com.nasmount.mobile;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ImageFolderSequenceTest {
    private static Models.Entry entry(String name, String path, boolean directory) {
        return new Models.Entry(name, path, directory);
    }

    @Test public void onlyImagesOfCurrentFolderAreInSequence() {
        ImageFolderSequence.showFolder("local", "",
                Arrays.asList(entry("a.jpg", "/photos/a.jpg", false),
                        entry("subfolder", "/photos/subfolder", true),
                        entry("notes.txt", "/photos/notes.txt", false),
                        entry("B.HEIC", "/photos/B.HEIC", false),
                        entry("c.gif", "/photos/c.gif", false)), true);
        String token = ImageFolderSequence.tokenFor("local", "", "/photos/B.HEIC");
        assertNotNull(token);
        ImageFolderSequence.Snapshot snapshot = ImageFolderSequence.resolve(token);
        assertNotNull(snapshot);
        assertEquals(3, snapshot.photos.size());
        assertEquals(1, snapshot.indexOf("/photos/B.HEIC"));
        assertEquals(2, snapshot.neighbor(1, 1));
        assertEquals(0, snapshot.neighbor(1, -1));
        assertEquals(-1, snapshot.neighbor(0, -1));
        assertEquals(-1, snapshot.neighbor(2, 1));
        assertEquals(-1, snapshot.indexOf("/other/a.jpg"));
    }

    @Test public void tokenCannotAccessOtherStorageOrOtherFolder() {
        ImageFolderSequence.showFolder("remote", "profile-A",
                Arrays.asList(entry("photo.png", "/home/photo.png", false)), true);
        String token = ImageFolderSequence.tokenFor("remote", "profile-A",
                "/home/photo.png");
        assertNotNull(token);
        assertNull(ImageFolderSequence.tokenFor("remote", "profile-B",
                "/home/photo.png"));
        assertNull(ImageFolderSequence.tokenFor("local", "profile-A",
                "/home/photo.png"));
        assertNull(ImageFolderSequence.tokenFor("remote", "profile-A",
                "/other/photo.png"));
        ImageFolderSequence.showFolder("local", "",
                Arrays.asList(entry("another.jpg", "/another.jpg", false)), true);
        assertNull(ImageFolderSequence.resolve(token));
    }

    @Test public void hiddenAndUnsupportedFilesAreExcluded() {
        ImageFolderSequence.showFolder("uri", "",
                Arrays.asList(entry(".hidden.png", "content://hidden", false),
                        entry("a.webp", "content://a", false),
                        entry("movie.mkv", "content://movie", false)), false);
        ImageFolderSequence.Snapshot snapshot = ImageFolderSequence.resolve(
                ImageFolderSequence.tokenFor("uri", "", "content://a"));
        assertNotNull(snapshot);
        assertEquals(1, snapshot.photos.size());
        assertEquals(-1, snapshot.indexOf("content://hidden"));
        assertEquals(-1, snapshot.neighbor(0, 1));
    }
}
