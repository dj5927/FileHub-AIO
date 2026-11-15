package com.nasmount.mobile;

import java.util.Locale;

/** Pure presentation policy shared by local, SAF and remote listing screens. */
final class FileViewOptions {
    static final int LIST = 0;
    static final int GRID = 1;
    static final int DETAILS = 2;
    static final int NAME_ASC = 0;
    static final int NAME_DESC = 1;
    static final int DATE_NEW = 2;
    static final int DATE_OLD = 3;
    static final int SIZE_LARGE = 4;
    static final int SIZE_SMALL = 5;

    private FileViewOptions() {}

    static boolean visible(String name, boolean hiddenFlag, boolean showHidden) {
        if (showHidden) return true;
        return !hiddenFlag && name != null && !name.startsWith(".");
    }

    static int compare(boolean aDir, String aName, long aSize, long aModified,
                       boolean bDir, String bName, long bSize, long bModified,
                       int sortMode) {
        // The file-manager convention keeps directories before files in every mode.
        if (aDir != bDir) return aDir ? -1 : 1;
        int order = 0;
        switch (sortMode) {
            case NAME_DESC:
                order = name(bName).compareTo(name(aName));
                break;
            case DATE_NEW:
                order = Long.compare(bModified, aModified);
                break;
            case DATE_OLD:
                order = Long.compare(aModified, bModified);
                break;
            case SIZE_LARGE:
                order = Long.compare(bSize, aSize);
                break;
            case SIZE_SMALL:
                order = Long.compare(aSize, bSize);
                break;
            default:
                order = name(aName).compareTo(name(bName));
        }
        return order != 0 ? order : name(aName).compareTo(name(bName));
    }

    static boolean matches(String filename, String query) {
        return name(filename).contains(name(query).trim());
    }

    private static String name(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
