package com.nasmount.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Persistent player settings. No credentials or codec binary content is stored here. */
final class PlayerSettings {
    static final String NAME = "nas_video_settings";
    static final String RATIO = "ratio";
    static final String SKIP_SECONDS = "skip_seconds";
    static final String HIDE_SUBTITLES = "hide_subtitles";
    static final String HIDE_FOLDERS = "subtitle_hide_folders";
    static final String CODEC_TREE = "codec_tree";
    static final String CODEC_TREE_LABEL = "codec_tree_label";
    static final String DEFAULT_SUBTITLE_SIZE = "subtitle_size_default_sp";
    static final String DEFAULT_SUBTITLE_LANGUAGE = "subtitle_language_default";
    static final String DEFAULT_SUBTITLE_SOURCE = "subtitle_source_default";
    static final String VIDEO_SUBTITLE_SIZE_PREFIX = "subtitle_size_video_";
    static final String VIDEO_SUBTITLE_SOURCE_PREFIX = "subtitle_source_video_";
    static final int DEFAULT_SUBTITLE_SP = 20;
    static final String DEFAULT_LANGUAGE = "ko";
    static final int[] SUBTITLE_SIZES = {12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48};
    static final String[] SUBTITLE_LANGUAGE_CODES = {"ko", "en", "ja", "zh", "es", "fr", "de", "ru", "pt", "hi", "id", "th", "vi", "und"};
    static final String[] SUBTITLE_LANGUAGE_LABELS = {"한국어", "English", "日本語", "中文", "Español", "Français", "Deutsch", "Русский", "Português", "हिन्दी", "Bahasa Indonesia", "ไทย", "Tiếng Việt", "언어 정보 없음"};
    static final int FIT = 0;
    static final int ZOOM = 1;
    static final int STRETCH = 2;

    private PlayerSettings() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    static int ratio(Context context) {
        int stored = prefs(context).getInt(RATIO, FIT);
        return stored >= FIT && stored <= STRETCH ? stored : FIT;
    }

    static int ratio(Context context,String folder){
        int value=FolderMediaSettings.integer(context,
                FolderMediaSettings.VIDEO,folder,RATIO,ratio(context));
        return value>=FIT&&value<=STRETCH?value:FIT;
    }
    static void setFolderRatio(Context context,String folder,int ratio){
        FolderMediaSettings.integerSet(context,FolderMediaSettings.VIDEO,folder,
                RATIO,Math.max(FIT,Math.min(STRETCH,ratio)));
    }
    static int skipSeconds(Context context) {
        int stored = prefs(context).getInt(SKIP_SECONDS, 10);
        return stored >= 5 && stored <= 60 ? stored : 10;
    }

    static int skipSeconds(Context context,String folder){
        int value=FolderMediaSettings.integer(context,
                FolderMediaSettings.VIDEO,folder,SKIP_SECONDS,skipSeconds(context));
        return value>=5&&value<=60?value:skipSeconds(context);
    }
    static int clampSubtitleSize(int size) {
        return Math.max(12, Math.min(48, size));
    }

    static int defaultSubtitleSize(Context context) {
        return clampSubtitleSize(prefs(context).getInt(
                DEFAULT_SUBTITLE_SIZE, DEFAULT_SUBTITLE_SP));
    }

    static void setDefaultSubtitleSize(Context context, int size) {
        prefs(context).edit().putInt(DEFAULT_SUBTITLE_SIZE,
                clampSubtitleSize(size)).apply();
    }

    static int videoSubtitleSize(Context context, String videoId) {
        int fallback = defaultSubtitleSize(context);
        if (videoId == null || videoId.isEmpty()) return fallback;
        return clampSubtitleSize(prefs(context).getInt(
                VIDEO_SUBTITLE_SIZE_PREFIX + videoId, fallback));
    }

    static int videoSubtitleSize(Context context,String videoId,String folder){
        return FolderMediaSettings.integer(context,FolderMediaSettings.VIDEO,
                folder,"subtitle_size",videoSubtitleSize(context,videoId));
    }
    static boolean hasFolderSubtitleSize(Context context,String videoId,String folder){
        return FolderMediaSettings.contains(context,FolderMediaSettings.VIDEO,
                folder,"subtitle_size")||hasVideoSubtitleSize(context,videoId);
    }
    static void setFolderSubtitleSize(Context context,String folder,int size){
        FolderMediaSettings.integerSet(context,FolderMediaSettings.VIDEO,
                folder,"subtitle_size",clampSubtitleSize(size));
    }
    static void resetFolderSubtitleSize(Context context,String folder,String videoId){
        FolderMediaSettings.remove(context,FolderMediaSettings.VIDEO,
                folder,"subtitle_size");
        resetVideoSubtitleSize(context,videoId);
    }
    static boolean hasVideoSubtitleSize(Context context, String videoId) {
        return videoId != null && prefs(context).contains(
                VIDEO_SUBTITLE_SIZE_PREFIX + videoId);
    }

    static void setVideoSubtitleSize(Context context, String videoId, int size) {
        if (videoId == null || videoId.isEmpty()) return;
        prefs(context).edit().putInt(VIDEO_SUBTITLE_SIZE_PREFIX + videoId,
                clampSubtitleSize(size)).apply();
    }

    static void resetVideoSubtitleSize(Context context, String videoId) {
        if (videoId != null)
            prefs(context).edit().remove(VIDEO_SUBTITLE_SIZE_PREFIX + videoId).apply();
    }

    /** Default is always AUTO (external over embedded). User choices are per-video. */
    static int videoSubtitleSource(Context context, String videoId) {
        if (videoId == null || videoId.isEmpty()) return SubtitleSourcePolicy.AUTO;
        return SubtitleSourcePolicy.normalize(prefs(context).getInt(
                VIDEO_SUBTITLE_SOURCE_PREFIX + videoId, SubtitleSourcePolicy.AUTO));
    }

    static int defaultSubtitleSource(Context context){
        return SubtitleSourcePolicy.normalize(prefs(context).getInt(
                DEFAULT_SUBTITLE_SOURCE,SubtitleSourcePolicy.AUTO));
    }
    static int videoSubtitleSource(Context context,String videoId,String folder){
        int fallback=videoId==null?defaultSubtitleSource(context):
                SubtitleSourcePolicy.normalize(prefs(context).getInt(
                        VIDEO_SUBTITLE_SOURCE_PREFIX+videoId,
                        defaultSubtitleSource(context)));
        return SubtitleSourcePolicy.normalize(
                FolderMediaSettings.integer(context,FolderMediaSettings.VIDEO,
                        folder,"subtitle_source",fallback));
    }
    static void setFolderSubtitleSource(Context context,String folder,int mode){
        FolderMediaSettings.integerSet(context,FolderMediaSettings.VIDEO,
                folder,"subtitle_source",SubtitleSourcePolicy.normalize(mode));
    }
    static void setVideoSubtitleSource(Context context, String videoId, int source) {
        if (videoId == null || videoId.isEmpty()) return;
        prefs(context).edit().putInt(VIDEO_SUBTITLE_SOURCE_PREFIX + videoId,
                SubtitleSourcePolicy.normalize(source)).apply();
    }

    static String defaultSubtitleLanguage(Context context) {
        String code = prefs(context).getString(DEFAULT_SUBTITLE_LANGUAGE, DEFAULT_LANGUAGE);
        for (String allowed : SUBTITLE_LANGUAGE_CODES)
            if (allowed.equals(code)) return code;
        return DEFAULT_LANGUAGE;
    }

    static void setDefaultSubtitleLanguage(Context context, String code) {
        for (String allowed : SUBTITLE_LANGUAGE_CODES) {
            if (allowed.equals(code)) {
                prefs(context).edit().putString(DEFAULT_SUBTITLE_LANGUAGE, code).apply();
                return;
            }
        }
        throw new IllegalArgumentException("Unsupported language: " + code);
    }

    static String languageLabel(String code) {
        if (code == null) return "언어 정보 없음";
        String language = code.toLowerCase(Locale.ROOT);
        if ("kor".equals(language)) return "한국어";
        if ("eng".equals(language)) return "English";
        if ("jpn".equals(language)) return "日本語";
        if ("zho".equals(language) || "chi".equals(language)) return "中文";
        for (int i = 0; i < SUBTITLE_LANGUAGE_CODES.length; i++)
            if (code.equalsIgnoreCase(SUBTITLE_LANGUAGE_CODES[i]) ||
                    language.startsWith(
                            SUBTITLE_LANGUAGE_CODES[i] + "-"))
                return SUBTITLE_LANGUAGE_LABELS[i];
        return code;
    }

    static void addHiddenFolder(Context context, String scope) {
        if (scope == null || scope.isEmpty()) return;
        Set<String> folders = new HashSet<>(
                prefs(context).getStringSet(HIDE_FOLDERS, new HashSet<>()));
        folders.add(FolderScopeFilter.normalize(scope));
        prefs(context).edit().putStringSet(HIDE_FOLDERS, folders).apply();
        AssignedFolderCatalog.Folder folder=AssignedFolderCatalog.fromVideoScope(scope);
        if(folder!=null)AssignedRoleExclusions.clear(context,
                AssignedFolderCatalog.VIDEO,folder.kind,folder.owner,folder.path);
    }

    static void removeHiddenFolder(Context context, String scope) {
        Set<String> folders = new HashSet<>(
                prefs(context).getStringSet(HIDE_FOLDERS, new HashSet<>()));
        folders.remove(scope);
        prefs(context).edit().putStringSet(HIDE_FOLDERS, folders).apply();
    }

    static boolean hideThisFile(Context context, String folderScope, String filename) {
        if (!prefs(context).getBoolean(HIDE_SUBTITLES, false)) return false;
        if (folderScope == null || !FolderScopeFilter.isSubtitleDocument(filename))
            return false;
        return AssignedRoleExclusions.videoAllowed(context,folderScope,
                prefs(context).getStringSet(HIDE_FOLDERS, new HashSet<>()));
    }
}
