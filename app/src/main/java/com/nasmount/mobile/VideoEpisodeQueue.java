package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Same-folder video queue independent of the file manager's current
 * sort-by-date/size. Natural numeric comparison keeps ep 01, 02, ... 10, 11.
 * We serialize only a bounded window around the selected episode into Intent.
 */
final class VideoEpisodeQueue {
    static final int MAX_TRANSFER = 120;

    static final class Episode {
        final String path;
        final String name;
        final long size;
        Episode(String path, String name, long size) {
            this.path = path;
            this.name = name;
            this.size = size;
        }
    }

    static final class Window {
        final List<Episode> episodes;
        final int currentIndex;
        Window(List<Episode> episodes, int currentIndex) {
            this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
            this.currentIndex = currentIndex;
        }
        Episode previous() {
            return currentIndex > 0 ? episodes.get(currentIndex - 1) : null;
        }
        Episode next() {
            return currentIndex + 1 < episodes.size() ?
                    episodes.get(currentIndex + 1) : null;
        }
    }

    private final List<Episode> ordered;

    private VideoEpisodeQueue(List<Episode> ordered) {
        this.ordered = Collections.unmodifiableList(ordered);
    }

    static boolean isVideo(String name) {
        return name != null && name.toLowerCase(Locale.ROOT)
                .matches(".*\\.(mp4|m4v|mov|mkv|avi|webm|3gp|ts|m2ts)$");
    }

    static VideoEpisodeQueue from(List<Episode> candidates) {
        ArrayList<Episode> filtered = new ArrayList<>();
        for (Episode e : candidates) {
            if (e != null && e.path != null && e.name != null &&
                    isVideo(e.name))
                filtered.add(e);
        }
        filtered.sort((a, b) -> {
            int compare = naturalCompare(a.name, b.name);
            return compare != 0 ? compare : a.path.compareTo(b.path);
        });
        return new VideoEpisodeQueue(filtered);
    }

    int size() { return ordered.size(); }

    Window window(String currentPath) {
        if (currentPath == null) return null;
        int index = -1;
        for (int i = 0; i < ordered.size(); i++)
            if (currentPath.equals(ordered.get(i).path)) {
                index = i;
                break;
            }
        if (index < 0) return null;
        int start = Math.max(0, index - MAX_TRANSFER / 2);
        start = Math.min(start, Math.max(0, ordered.size() - MAX_TRANSFER));
        int end = Math.min(ordered.size(), start + MAX_TRANSFER);
        return new Window(ordered.subList(start, end), index - start);
    }

    /** Case-insensitive natural sort, comparing digit runs without integer overflow. */
    static int naturalCompare(String left, String right) {
        if (left == null || right == null)
            return left == null ? (right == null ? 0 : -1) : 1;
        String a = left.toLowerCase(Locale.ROOT);
        String b = right.toLowerCase(Locale.ROOT);
        int ia = 0, ib = 0;
        while (ia < a.length() && ib < b.length()) {
            boolean digitA = a.charAt(ia) >= '0' && a.charAt(ia) <= '9';
            boolean digitB = b.charAt(ib) >= '0' && b.charAt(ib) <= '9';
            if (digitA && digitB) {
                int ea = ia, eb = ib;
                while (ea < a.length() && a.charAt(ea) >= '0' &&
                        a.charAt(ea) <= '9') ea++;
                while (eb < b.length() && b.charAt(eb) >= '0' &&
                        b.charAt(eb) <= '9') eb++;
                int sa = ia, sb = ib;
                while (sa < ea - 1 && a.charAt(sa) == '0') sa++;
                while (sb < eb - 1 && b.charAt(sb) == '0') sb++;
                int lenA = ea - sa, lenB = eb - sb;
                if (lenA != lenB) return Integer.compare(lenA, lenB);
                for (int i = 0; i < lenA; i++) {
                    int c = Character.compare(a.charAt(sa + i), b.charAt(sb + i));
                    if (c != 0) return c;
                }
                // 02 and 2 represent the same episode; sort deterministically.
                int width = (ea - ia) - (eb - ib);
                if (width != 0) return width;
                ia = ea;
                ib = eb;
                continue;
            }
            int cmp = Character.compare(a.charAt(ia), b.charAt(ib));
            if (cmp != 0) return cmp;
            ia++;
            ib++;
        }
        return Integer.compare(a.length() - ia, b.length() - ib);
    }
}
