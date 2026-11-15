package com.nasmount.mobile;

import java.util.Locale;

/** Explicit file-type routing. Never treat an APK or unknown binary as text. */
final class EditableFileTypes {
    private EditableFileTypes() {}

    static boolean isApk(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".apk");
    }

    static boolean isEditableText(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(txt|text|md|markdown|log|json|xml|csv|tsv|ini|cfg|conf|" +
                "yaml|yml|properties|nfo|srt|smi|vtt|ass|ssa|html|htm|css|js|ts|" +
                "java|kt|py|sh|bat|cmd|sql|env|tex|svg)$") ||
                lower.equals("readme") || lower.equals("license") ||
                lower.equals("changelog") || lower.equals(".gitignore");
    }
}
