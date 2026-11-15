package com.nasmount.mobile;

/**
 * WebView's horizontal scroll width is the only content extent available
 * while JavaScript is deliberately disabled. Paged XHTML must therefore
 * overflow horizontally: clipping html/body hides every page after the
 * first and makes the reader advance a chapter prematurely.
 *
 * A full screen of page columns occupies exactly one viewport:
 * side padding = m and column gap = 2m means 1 column per phone screen
 * or 2 columns per Fold screen, each spread exactly viewportWidth wide.
 */
final class EpubPaginationPolicy {
    private EpubPaginationPolicy(){}
    static String css(boolean spread,int margin){
        int m=Math.max(8,Math.min(50,margin));
        return "html{margin:0!important;padding:0!important;"+
                "width:100%!important;height:100%!important;"+
                "overflow-x:auto!important;overflow-y:hidden!important;}"+
                "body{box-sizing:border-box!important;"+
                "margin:0!important;width:100vw!important;"+
                "min-width:0!important;max-width:none!important;"+
                "height:calc(100vh - 20px)!important;"+
                "min-height:0!important;max-height:none!important;"+
                // Crucial: horizontal overflow of new multicolumn pages
                // must contribute to WebView.computeHorizontalScrollRange.
                "overflow:visible!important;"+
                "column-fill:auto!important;"+
                "column-count:"+(spread?2:1)+"!important;"+
                "column-width:auto!important;"+
                "column-gap:"+(m*2)+"px!important;}";
    }
    static String injectAfterBookStyles(String original,String injected){
        // Later book styles must never undo the reader's column pagination.
        String safe=java.util.regex.Matcher.quoteReplacement(injected);
        if(original.matches("(?is).*<head\\b[^>]*>.*")){
            if(original.matches("(?is).*</head\\s*>.*"))
                return original.replaceFirst("(?i)(</head\\s*>)",safe+"$1");
            return original.replaceFirst("(?i)(<head\\b[^>]*>)","$1"+safe);
        }
        if(original.matches("(?is).*<html\\b[^>]*>.*"))
            return original.replaceFirst("(?i)(<html\\b[^>]*>)",
                    "$1<head>"+safe+"</head>");
        return "<!doctype html><html><head>"+injected+"</head><body>"+
                original+"</body></html>";
    }
    static int target(int position,int direction,int viewport,int extent){
        long step=Math.max(1,viewport);
        long dest=(long)position+(direction>=0?step:-step);
        return (int)Math.max(0,Math.min((long)Math.max(0,extent),dest));
    }
    static int restoreTarget(int storedOffset,int viewport,int extent){
        // On reopening after a font, Fold or toolbar-height change, an
        // arbitrary fractional X can land in the middle of a visual page.
        // Floor to the previous complete page; never skip unread text.
        int page=Math.max(1,viewport);
        long safe=Math.max(0,Math.min((long)Math.max(0,extent),
                (long)Math.max(0,storedOffset)));
        return (int)Math.min((long)Math.max(0,extent),
                safe/page*page);
    }
    static int maxScroll(int contentWidth,int viewport){
        return Math.max(0,contentWidth-Math.max(1,viewport));
    }
    static boolean hasPage(int position,int direction,int viewport,int extent){
        return target(position,direction,viewport,extent)!=position;
    }
}
