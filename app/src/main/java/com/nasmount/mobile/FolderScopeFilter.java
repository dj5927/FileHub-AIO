package com.nasmount.mobile;

import java.util.Locale;
import java.util.Set;

/** Pure matching policy for subtitle and NFO hiding, including child folders. */
final class FolderScopeFilter {
    private FolderScopeFilter() {}

    static String normalize(String scope) {
        if (scope == null) return "";
        scope = scope.replace('\\', '/');
        while (scope.endsWith("/") && scope.length() > 1) scope = scope.substring(0, scope.length()-1);
        return scope;
    }

    static boolean isSubtitleDocument(String name) {
        if (name == null) return false;
        String file = name.toLowerCase(Locale.ROOT);
        return file.endsWith(".srt") || file.endsWith(".smi")
                || file.endsWith(".ass") || file.endsWith(".ssa")
                || file.endsWith(".vtt") || file.endsWith(".sub")
                || file.endsWith(".sup") || file.endsWith(".idx")
                || file.endsWith(".txt") || file.endsWith(".nfo");
    }

    static boolean folderCovered(String currentScope, Set<String> selectedFolders) {
        if (selectedFolders == null || currentScope == null) return false;
        String current = normalize(currentScope);
        for (String entry : selectedFolders) {
            String root = normalize(entry);
            if (root.isEmpty()) continue;
            // The final / boundary avoids matching sibling "Movies2" for "Movies".
            if (current.equals(root) || current.startsWith(root + "/"))
                return true;
            if (root.endsWith("/") && current.startsWith(root)) return true;
        }
        return false;
    }
}
