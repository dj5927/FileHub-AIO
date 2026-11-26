package com.nasmount.mobile;

import java.util.Locale;

/** Display-only file categories; never use this to decide file execution or access. */
final class FileIconType {
    private FileIconType() {}
    static String kind(String name) {
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".tar.gz")||n.endsWith(".tar.bz2")||n.endsWith(".tar.xz")||n.endsWith(".tar.zst"))return "archive";
        int dot=n.lastIndexOf('.');
        if(dot<0||dot==n.length()-1)return "file";
        switch(n.substring(dot+1)){
            case "jpg":case "jpeg":case "png":case "gif":case "webp":case "bmp":case "heic":case "heif":case "tif":case "tiff":case "svg":case "avif":case "ico":return "image";
            case "mp3":case "m4a":case "aac":case "wav":case "flac":case "ogg":case "opus":case "wma":case "aiff":case "aif":case "mka":return "audio";
            case "mp4":case "m4v":case "mov":case "mkv":case "avi":case "webm":case "3gp":case "ts":case "m2ts":case "wmv":case "flv":case "mpeg":case "mpg":return "video";
            case "xls":case "xlsx":case "xlsm":case "xlsb":case "xlt":case "xltx":case "ods":case "csv":case "tsv":return "spreadsheet";
            case "doc":case "docx":case "docm":case "dot":case "dotx":case "odt":case "rtf":return "word";
            case "ppt":case "pptx":case "pptm":case "pps":case "ppsx":case "odp":return "presentation";
            case "hwp":case "hwpx":return "hangul";
            case "pdf":return "pdf";
            case "txt":case "log":case "md":case "ini":case "nfo":return "text";
            case "zip":case "rar":case "7z":case "tar":case "gz":case "bz2":case "xz":case "zst":case "tgz":case "cab":case "iso":case "lz":return "archive";
            case "epub":case "mobi":case "azw":case "azw3":case "fb2":return "book";
            case "cbz":case "cbr":case "cb7":case "cbt":return "comic";
            case "apk":case "apks":case "xapk":case "aab":return "android";
            case "exe":case "msi":case "bat":case "cmd":case "com":return "application";
            case "srt":case "ass":case "ssa":case "vtt":case "smi":case "sub":return "subtitle";
            case "json":case "xml":case "html":case "htm":case "js":case "css":case "py":case "java":case "kt":case "dart":case "c":case "cpp":case "sh":case "ps1":return "code";
            case "ai":case "psd":case "xcf":case "eps":return "design";
            default:return "file";
        }
    }
    static int color(String kind) {
        switch(kind){
            case "spreadsheet":return 0xFF258B5B;
            case "word":return 0xFF397DD0;
            case "presentation":return 0xFFDC7044;
            case "hangul":return 0xFF3B95C8;
            case "pdf":return 0xFFD85151;
            case "text":return 0xFF8396A8;
            case "archive":return 0xFFC29445;
            case "book":case "comic":return 0xFF9471C9;
            case "android":return 0xFF65A94E;
            case "application":case "code":return 0xFF7892B5;
            case "subtitle":return 0xFF4BA5A0;
            case "design":return 0xFFE49A45;
            default:return Ui.TEAL;
        }
    }
    static String mark(String kind){
        switch(kind){
            case "spreadsheet":return "X";
            case "word":return "W";
            case "presentation":return "P";
            case "hangul":return "HWP";
            case "pdf":return "PDF";
            case "text":return "TXT";
            case "archive":return "ZIP";
            case "android":return "APK";
            case "application":return "EXE";
            case "subtitle":return "CC";
            case "code":return "</>";
            case "design":return "ART";
            default:return "";
        }
    }
}
