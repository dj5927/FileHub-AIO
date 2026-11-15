package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reorder only visible favorites; retain retired entries in their slots. */
final class FavoriteOrderPolicy {
    private FavoriteOrderPolicy() {}

    static List<String> move(List<String> current, int from, int to) {
        if (current == null || from < 0 || to < 0 ||
                from >= current.size() || to >= current.size())
            throw new IllegalArgumentException("잘못된 즐겨찾기 이동 위치입니다.");
        List<String> result = new ArrayList<>(current);
        if (from != to) result.add(to, result.remove(from));
        return result;
    }

    static List<Models.Favorite> reorderVisible(List<Models.Favorite> saved,
                                                 List<String> originalVisible,
                                                 List<String> desiredVisible) {
        if (saved == null || originalVisible == null || desiredVisible == null ||
                originalVisible.size() != desiredVisible.size())
            throw new IllegalArgumentException("즐겨찾기 순서가 일치하지 않습니다.");
        Set<String> originals = new HashSet<>(originalVisible);
        Set<String> requested = new HashSet<>(desiredVisible);
        if (originals.size() != originalVisible.size() ||
                requested.size() != desiredVisible.size() || !originals.equals(requested))
            throw new IllegalArgumentException("즐겨찾기 항목이 변경되었습니다.");
        List<Models.Favorite> result = new ArrayList<>(saved);
        java.util.Map<String,Models.Favorite> byKey = new java.util.HashMap<>();
        for (Models.Favorite f : saved) {
            if (originals.contains(f.key()) && byKey.put(f.key(), f) != null)
                throw new IllegalArgumentException("중복된 즐겨찾기입니다.");
        }
        if (byKey.size() != originals.size())
            throw new IllegalArgumentException("즐겨찾기가 삭제되었거나 변경되었습니다.");
        int cursor = 0;
        for (int i=0; i<result.size(); i++) {
            if (originals.contains(result.get(i).key()))
                result.set(i, byKey.get(desiredVisible.get(cursor++)));
        }
        return result;
    }
}
