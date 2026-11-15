package com.nasmount.mobile;

import java.util.Locale;

/** Office documents intentionally never route through the plain text editor. */
final class OfficeFileTypes {
    private OfficeFileTypes() {}

    static boolean supports(String filename) {
        if (filename == null) return false;
        String n = filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".ods") ||
                n.endsWith(".hwp") || n.endsWith(".hwpx") ||
                n.endsWith(".docx") || n.endsWith(".pptx") ||
                n.endsWith(".doc") || n.endsWith(".ppt");
    }

    static boolean isSpreadsheet(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls") || n.endsWith(".ods");
    }

    static String mode(String name) {
        if (name == null) return "unknown";
        String n = name.toLowerCase(Locale.ROOT);
        if (isSpreadsheet(n)) return "sheet";
        if (n.endsWith(".hwp") || n.endsWith(".hwpx")) return "hwp";
        if (n.endsWith(".docx")) return "word";
        if (n.endsWith(".pptx")) return "slides";
        // Legacy Office binary formats must never be passed to ZIP parsers.
        if (n.endsWith(".doc") || n.endsWith(".ppt")) return "legacy";
        return "unknown";
    }

    static String mime(String filename) {
        String n = filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx"))
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".ods"))
            return "application/vnd.oasis.opendocument.spreadsheet";
        if (n.endsWith(".hwpx")) return "application/hwp+zip";
        if (n.endsWith(".hwp")) return "application/x-hwp";
        if (n.endsWith(".docx"))
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".pptx"))
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        return "application/octet-stream";
    }
}
