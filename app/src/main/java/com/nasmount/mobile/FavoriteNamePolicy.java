package com.nasmount.mobile;

/** Bookmark labels are independent from original file/folder names.
 * Empty input resets the label to the original name. */
final class FavoriteNamePolicy {
    private FavoriteNamePolicy() {}

    static String normalize(String input, String originalName) {
        if (input == null) throw new IllegalArgumentException("즐겨찾기 이름이 없습니다.");
        String proposed = input.trim();
        if (proposed.isEmpty() || proposed.equals(originalName)) return "";
        if (proposed.codePointCount(0, proposed.length()) > 80)
            throw new IllegalArgumentException("즐겨찾기 이름은 최대 80글자입니다.");
        for (int i = 0; i < proposed.length(); i++) {
            char ch = proposed.charAt(i);
            if (Character.isISOControl(ch))
                throw new IllegalArgumentException("줄바꿈이나 제어문자는 사용할 수 없습니다.");
        }
        return proposed;
    }
}
