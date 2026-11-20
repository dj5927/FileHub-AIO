package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * In-process snapshot of the entire current folder, not just the rows
 * currently inflated by directory pagination. Avoid passing thousands of
 * URLs and file names through Android's limited Binder Intent payload.
 */
final class ImageFolderSequence {
    private ImageFolderSequence() {}

    static final class Photo {
        final String name;
        final String locator;
        Photo(String name, String locator) {
            this.name = name;
            this.locator = locator;
        }
    }

    static final class Snapshot {
        final String token;
        final String kind;
        final String profileId;
        final List<Photo> photos;

        Snapshot(String token, String kind, String profileId, List<Photo> photos) {
            this.token = token;
            this.kind = kind;
            this.profileId = profileId;
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
        }

        int indexOf(String locator) {
            if (locator == null) return -1;
            for (int index = 0; index < photos.size(); index++)
                if (locator.equals(photos.get(index).locator)) return index;
            return -1;
        }

        int neighbor(int index, int direction) {
            int next = index + direction;
            return direction != 0 && next >= 0 && next < photos.size()
                    ? next : -1;
        }
    }

    private static volatile Snapshot current;

    static void showFolder(String kind, String profileId,
                           List<Models.Entry> entries, boolean showHidden) {
        List<Photo> photos = new ArrayList<>();
        for (Models.Entry entry : entries) {
            if (entry.directory || !MediaFileTypes.isImage(entry.name) ||
                    !FileViewOptions.visible(entry.name,
                            entry.name.startsWith("."), showHidden))
                continue;
            if (entry.path == null || entry.path.isEmpty()) continue;
            photos.add(new Photo(entry.name, entry.path));
        }
        current = new Snapshot(UUID.randomUUID().toString(), kind,
                profileId == null ? "" : profileId, photos);
    }

    static String tokenFor(String kind, String profileId, String locator) {
        Snapshot snapshot = current;
        if (snapshot == null || !snapshot.kind.equals(kind) ||
                !snapshot.profileId.equals(profileId == null ? "" : profileId) ||
                snapshot.indexOf(locator) < 0)
            return null;
        return snapshot.token;
    }

    static Snapshot resolve(String token) {
        Snapshot snapshot = current;
        return snapshot != null && snapshot.token.equals(token) ? snapshot : null;
    }
}
