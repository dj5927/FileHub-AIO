package com.nasmount.mobile;

import android.content.Context;
import android.content.res.Configuration;

/** Persisted interface appearance, independent of the immersive movie player. */
final class AppearanceSettings {
    static final int SYSTEM = 0;
    static final int LIGHT = 1;
    static final int DARK = 2;
    private static final String PREFS = "appearance";
    private static final String KEY_MODE = "theme_mode";
    static final String[] LABELS = {"시스템 설정에 맞춤", "라이트 모드", "다크 모드"};

    private AppearanceSettings() {}

    static int normalized(int value) {
        return value < SYSTEM || value > DARK ? LIGHT : value;
    }

    static int mode(Context context) {
        return normalized(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, LIGHT));
    }

    static void choose(Context context, int selected) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_MODE, normalized(selected)).apply();
    }

    static boolean isDarkFor(int mode, boolean deviceDark) {
        return mode == DARK || (mode == SYSTEM && deviceDark);
    }

    static boolean isDark(Context context) {
        boolean deviceDark = (context.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return isDarkFor(mode(context), deviceDark);
    }
}
