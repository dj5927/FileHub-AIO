package com.nasmount.mobile;

/**
 * EPUB reading surface uses no transient loading overlays. Only an actual
 * read/parse error may cover the center of the book with diagnostic text.
 */
final class EpubReaderStatus {
    private EpubReaderStatus(){}

    static boolean showCenterMessage(boolean initialLoading,
                                     boolean chapterLoading,boolean hasError){
        return hasError;
    }
}
