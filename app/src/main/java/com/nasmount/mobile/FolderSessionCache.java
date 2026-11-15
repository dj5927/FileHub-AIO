package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory directory snapshot for ONE running application process.
 *
 * Never caches credentials, persists to disk or reloads behind the user's back.
 * Errors are not cached. Explicit refresh or successful writes invalidate the
 * relevant connection, and process restart naturally creates an empty cache.
 */
final class FolderSessionCache {
    interface Loader {
        List<Models.Entry> load() throws Exception;
    }

    private final Map<String, List<Models.Entry>> lists = new ConcurrentHashMap<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Long> versions = new ConcurrentHashMap<>();

    static String key(Models.RemoteProfile profile, String path) {
        return profile.id + "|" + profile.protocol + "|" + profile.host +
                "|" + profile.port + "|" + profile.root + "|" +
                RemoteProvider.relativePath(path);
    }

    List<Models.Entry> peek(Models.RemoteProfile profile, String folder) {
        return lists.get(key(profile, folder));
    }

    List<Models.Entry> load(Models.RemoteProfile profile, String folder,
                            Loader loader) throws Exception {
        String identity = key(profile, folder);
        List<Models.Entry> current = lists.get(identity);
        if (current != null) return current;
        Object lock = locks.computeIfAbsent(identity, ignored -> new Object());
        synchronized (lock) {
            current = lists.get(identity);
            if (current != null) return current;
            long version = versions.getOrDefault(profile.id, 0L);
            List<Models.Entry> downloaded = Collections.unmodifiableList(
                    new ArrayList<>(loader.load()));
            // A slow in-flight request must not reinsert a stale snapshot
            // after a manual refresh or successful server write.
            if (versions.getOrDefault(profile.id, 0L) == version)
                lists.put(identity, downloaded);
            return downloaded;
        }
    }

    Integer count(String key) {
        return counts.get(key);
    }

    void rememberCount(String key, int value) {
        if (value >= 0) counts.put(key, value);
    }

    void invalidateProfile(Models.RemoteProfile profile) {
        versions.merge(profile.id, 1L, Long::sum);
        String prefix = profile.id + "|";
        for (String key : lists.keySet())
            if (key.startsWith(prefix)) lists.remove(key);
        for (String key : counts.keySet())
            if (key.startsWith(prefix)) counts.remove(key);
    }

    void clearCounts() {
        counts.clear();
    }
}
