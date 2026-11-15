package com.nasmount.mobile;

import java.util.Locale;

final class MediaFileTypes {
    private MediaFileTypes() {}
    static boolean isAudio(String name) {
        if (name == null) return false;
        return name.toLowerCase(Locale.ROOT).matches(
                ".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|wma|aiff|aif)$");
    }
    static boolean isImage(String name) {
        if (name == null) return false;
        return name.toLowerCase(Locale.ROOT).matches(
                ".*\\.(jpg|jpeg|png|webp|bmp|gif|heic|heif)$");
    }
}
