package com.nasmount.mobile;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure path/selection safety rules; unit test without Android device. */
final class FileOperationPolicy {
    private FileOperationPolicy() {}

    static boolean childOrSame(String root, String possibleChild) {
        String a = trim(root);
        String b = trim(possibleChild);
        if (a.equals(b)) return true;
        return b.startsWith(a.endsWith("/") ? a : a + "/");
    }

    private static String trim(String value) {
        String clean = value.replace('\\', '/');
        while (clean.length() > 1 && clean.endsWith("/"))
            clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    static void validateBatchNames(List<String> names) {
        if (names.isEmpty()) throw new IllegalArgumentException("선택한 파일이 없습니다.");
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (name == null || name.isEmpty() || name.equals(".") ||
                    name.equals("..") || name.contains("/") ||
                    name.contains("\\") || name.indexOf('\0') >= 0)
                throw new IllegalArgumentException("파일 이름이 올바르지 않습니다.");
            if (!unique.add(name.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("같은 이름을 가진 선택 항목이 겹칩니다: " + name);
        }
    }
}
