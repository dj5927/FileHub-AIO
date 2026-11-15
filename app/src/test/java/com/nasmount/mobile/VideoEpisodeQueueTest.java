package com.nasmount.mobile;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import static org.junit.Assert.*;

public final class VideoEpisodeQueueTest {
    private static VideoEpisodeQueue.Episode ep(String name) {
        return new VideoEpisodeQueue.Episode("/shows/" + name, name, 123);
    }

    @Test public void anohanaEpisodesNaturalSortRegardlessOfDirectoryListingOrder() {
        VideoEpisodeQueue queue = VideoEpisodeQueue.from(Arrays.asList(
                ep("Anohana - 04.mkv"), ep("Anohana - 05.mkv"),
                ep("Anohana - 06.mkv"), ep("Anohana - 10.mkv"),
                ep("Anohana - 07.mkv"), ep("Anohana - 11 END.mkv"),
                ep("Anohana - 02.mkv"), ep("Anohana - 08.mkv"),
                ep("Anohana - 09.mkv"), ep("Anohana - 01.mkv"),
                ep("Anohana - 03.mkv"), ep("unrelated.srt")));
        assertEquals(11, queue.size());
        VideoEpisodeQueue.Window first = queue.window("/shows/Anohana - 01.mkv");
        assertEquals("Anohana - 02.mkv", first.next().name);
        assertNull(first.previous());
        VideoEpisodeQueue.Window middle = queue.window("/shows/Anohana - 09.mkv");
        assertEquals("Anohana - 08.mkv", middle.previous().name);
        assertEquals("Anohana - 10.mkv", middle.next().name);
        VideoEpisodeQueue.Window finalEpisode =
                queue.window("/shows/Anohana - 11 END.mkv");
        assertNull(finalEpisode.next());
        assertEquals("Anohana - 10.mkv", finalEpisode.previous().name);
    }

    @Test public void largeDigitRunsNeverOverflowOrLexicographicallyMisorder() {
        assertTrue(VideoEpisodeQueue.naturalCompare(
                "episode 9.mp4", "episode 10.mp4") < 0);
        assertTrue(VideoEpisodeQueue.naturalCompare(
                "movie 99999999999999999999.mkv",
                "movie 100000000000000000000.mkv") < 0);
        assertTrue(VideoEpisodeQueue.naturalCompare(
                "part2.MKV", "part11.mkv") < 0);
    }

    @Test public void onlyVideoExtensionsBecomeEpisodes() {
        VideoEpisodeQueue queue = VideoEpisodeQueue.from(Arrays.asList(
                ep("1.mp4"), ep("2.mkv"), ep("2.srt"), ep("2.nfo"),
                ep("preview.jpg"), ep("archive.zip"), ep("3.webm")));
        assertEquals(3, queue.size());
        assertEquals("2.mkv", queue.window("/shows/1.mp4").next().name);
        assertNull(queue.window("/shows/2.srt"));
    }

    @Test public void sameFolderQueueDoesNotInheritUnrelatedPaths() {
        VideoEpisodeQueue queue = VideoEpisodeQueue.from(Arrays.asList(
                new VideoEpisodeQueue.Episode("/a/1.mp4", "1.mp4", 1),
                new VideoEpisodeQueue.Episode("/a/2.mp4", "2.mp4", 2)));
        assertNull(queue.window("/b/2.mp4"));
    }

    @Test public void transferredQueueIsBoundedAroundClickedEpisode() {
        List<VideoEpisodeQueue.Episode> entries = new ArrayList<>();
        for (int i = 1; i <= 300; i++) {
            String file = String.format(java.util.Locale.ROOT, "ep%03d.mkv", i);
            entries.add(ep(file));
        }
        Collections.reverse(entries);
        VideoEpisodeQueue queue = VideoEpisodeQueue.from(entries);
        VideoEpisodeQueue.Window window = queue.window("/shows/ep225.mkv");
        assertNotNull(window);
        assertEquals(VideoEpisodeQueue.MAX_TRANSFER, window.episodes.size());
        assertEquals("ep224.mkv", window.previous().name);
        assertEquals("ep226.mkv", window.next().name);
        assertEquals("ep225.mkv", window.episodes.get(window.currentIndex).name);
    }

    @Test public void oneFileOrUnknownFileHasNoNeighbor() {
        VideoEpisodeQueue queue = VideoEpisodeQueue.from(Collections.singletonList(
                ep("only.mp4")));
        VideoEpisodeQueue.Window window = queue.window("/shows/only.mp4");
        assertNotNull(window);
        assertNull(window.previous());
        assertNull(window.next());
    }
}
