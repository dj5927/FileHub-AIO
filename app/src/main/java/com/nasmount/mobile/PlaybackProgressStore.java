package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Video watch progress, stored locally and keyed by a one-way SHA-256 digest.
 * Never writes remote credentials, NAS paths or content URIs into preferences.
 */
final class PlaybackProgressStore {
    private static final String PREF_NAME = "video_watch_progress";
    private PlaybackProgressStore() {}

    static String idFor(String identity) {
        if (identity == null || identity.isEmpty())
            throw new IllegalArgumentException("Missing video identity");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) hex.append(String.format("%02x", part & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException unsupported) {
            throw new IllegalStateException("SHA-256 unavailable", unsupported);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    static long resumePosition(Context c, String id) {
        if (id == null) return -1;
        SharedPreferences state = prefs(c);
        long position = state.getLong(id + ".position", -1);
        long duration = state.getLong(id + ".duration", -1);
        return PlaybackResumePolicy.canResume(position, duration) ? position : -1;
    }

    static void save(Context c, String id, long position, long duration) {
        if (id == null) return;
        if (!PlaybackResumePolicy.canResume(position, duration)) {
            clear(c, id);
            return;
        }
        prefs(c).edit().putLong(id + ".position", position)
                .putLong(id + ".duration", duration > 0 ? duration : -1)
                .apply();
    }

    static void clear(Context c, String id) {
        if (id == null) return;
        prefs(c).edit().remove(id + ".position")
                .remove(id + ".duration").apply();
    }
}
