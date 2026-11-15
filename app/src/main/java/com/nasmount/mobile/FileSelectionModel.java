package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Stable, ordered multi-selection shared by list/grid file screens. */
final class FileSelectionModel<T> {
    private final LinkedHashMap<String, T> entries = new LinkedHashMap<>();

    boolean contains(String key) { return entries.containsKey(key); }
    int size() { return entries.size(); }
    boolean isEmpty() { return entries.isEmpty(); }
    void clear() { entries.clear(); }
    List<T> snapshot() { return new ArrayList<>(entries.values()); }

    void toggle(String key, T item) {
        if (entries.containsKey(key)) entries.remove(key);
        else entries.put(key, item);
    }

    void selectAll(LinkedHashMap<String, T> visible) {
        boolean alreadyAll = !visible.isEmpty() &&
                entries.keySet().containsAll(visible.keySet());
        if (alreadyAll) entries.clear();
        else {
            entries.clear();
            entries.putAll(visible);
        }
    }
}
