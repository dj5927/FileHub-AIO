package com.nasmount.mobile;

/**
 * WebView's horizontal scroll width is the only content extent available
 * while JavaScript is deliberately disabled. Paged XHTML must therefore
 * overflow horizontally: clipping html/body hides every page after the
 * first and makes the reader advance a chapter prematurely.
 *
 * A full screen of page columns occupies exactly one viewport. Side padding
 * on BODY plus a 2*m column gap made Chromium omit the last right margin
 * from its scroll range. An actual 642px-wide book page started at x=6420,
 * but the final allowed WebView offset was x=6402.
 *
 * No body side padding and no column gap: the paginated columns now start
 * on exact viewport boundaries. Margins live inside each page's child
 * blocks. One column fills a phone page; two fill a Fold spread.
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
                "column-gap:0!important;}"+
                "body > * {box-sizing:border-box!important;"+
                "padding-left:"+m+"px!important;"+
                "padding-right:"+m+"px!important;}";
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
        // Snap first: minor Chromium scroll offsets must not accumulate.
        long current=Math.round(Math.max(0,position)/(double)step);
        long dest=(current+(direction>=0?1:-1))*step;
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
    /** History fractions are anchored at page STARTS; use nearest, not floor. */
    static int restoreNearest(int storedOffset,int viewport,int extent){
        int page=Math.max(1,viewport);
        long safe=Math.max(0,Math.min((long)Math.max(0,extent),storedOffset));
        long aligned=Math.round(safe/(double)page)*page;
        return (int)Math.min((long)Math.max(0,extent),aligned);
    }
    /** Previous-chapter navigation must land on its actual final boundary. */
    static int lastPageStart(int viewport,int extent){
        return Math.max(0,extent);
    }
    static int pageIndex(int position,int viewport){
        return (int)Math.max(0,Math.round(Math.max(0,position)/
                (double)Math.max(1,viewport)));
    }
    static int pageCount(int viewport,int extent){
        int width=Math.max(1,viewport);
        return 1+(Math.max(0,extent)+width-1)/width;
    }
    static boolean hasPage(int position,int direction,int viewport,int extent){
        return target(position,direction,viewport,extent)!=position;
    }
}
