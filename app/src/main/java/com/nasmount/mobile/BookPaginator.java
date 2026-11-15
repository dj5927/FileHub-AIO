package com.nasmount.mobile;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.util.ArrayList;
import java.util.List;

/** Measure wrapped *visual lines* at current screen width/font/spacing.
 * Paging follows real lines, not an arbitrary number of characters. */
final class BookPaginator {
    private BookPaginator(){}
    static List<Integer> starts(String text,TextPaint paint,int width,int height,
                                float lineSpacing){
        if(width<32||height<32)throw new IllegalArgumentException("페이지 영역이 너무 좁습니다.");
        List<Integer> out=new ArrayList<>();
        out.add(0);
        if(text.isEmpty())return out;
        int current=0;
        final TextPaint bookPaint=KoreanReadingLayout.bookPaint(paint);
        // IMPORTANT: Every page must be laid out FROM ITS OWN START.
        // The old implementation accumulated line heights across arbitrary
        // 12K-character chunks. Android's high-quality paragraph breaker
        // chooses different word-wrap points when a new TextView starts in
        // the middle of a paragraph. Those extra visual lines were clipped
        // at the bottom and then silently skipped on the next page.
        while(current<text.length()){
            // Obsolete whole-book index requests from font/viewport changes
            // must be cancellable; never keep a canceled 6,000-page task
            // competing with visible page turns.
            if(Thread.currentThread().isInterrupted())
                throw new IllegalStateException("이전 페이지 계산이 취소되었습니다.");
            // This bounded window should be several times longer than one
            // page, while avoiding a multi-megabyte StaticLayout allocation.
            float averageGlyph=Math.max(1f,bookPaint.getTextSize()*.56f);
            int estimatedLines=Math.max(1,(int)Math.ceil(
                    height/Math.max(1f,bookPaint.getFontSpacing()*lineSpacing)));
            int charsPerLine=Math.max(1,(int)Math.ceil(width/averageGlyph));
            int lookahead=Math.max(2048,Math.min(16000,
                    estimatedLines*charsPerLine*3+256));
            int limit=Math.min(text.length(),current+lookahead);
            if(limit<text.length()&&limit>current&&
                    Character.isHighSurrogate(text.charAt(limit-1)))limit--;
            StaticLayout layout=KoreanReadingLayout.configure(
                    StaticLayout.Builder.obtain(text,current,limit,
                            bookPaint,width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0,lineSpacing)
                    .setIncludePad(false))
                    .build();
            int end=limit;
            int firstOverflowLine=-1;
            for(int line=0;line<layout.getLineCount();line++){
                if(layout.getLineBottom(line)>height){
                    end=layout.getLineStart(line);
                    firstOverflowLine=line;
                    break;
                }
            }
            if(firstOverflowLine>=6&&end>current){
                // Only reconsider the last two visible lines; a complete
                // Korean sentence/paragraph can move to the next page but
                // never at the cost of an almost empty current page.
                int recentStart=layout.getLineStart(firstOverflowLine-2);
                end=KoreanPageBoundary.choose(text,current,end,recentStart);
            }
            if(end<=current){
                // Tiny floating windows: always make forward progress and
                // never lose text, even if a single line is taller than the
                // available canvas.
                end=Math.min(limit,layout.getLineEnd(0));
                if(end<=current)end=Math.min(text.length(),current+1);
            }
            if(end<text.length()&&end>current&&
                    Character.isHighSurrogate(text.charAt(end-1)))end--;
            if(end<=current)end=Math.min(text.length(),current+2);
            current=end;
            if(current<text.length())out.add(current);
        }
        return out;
    }
    static int pageFor(List<Integer> starts,int charOffset){
        int low=0,high=starts.size()-1;
        while(low<=high){
            int middle=(low+high)>>>1;
            if(starts.get(middle)<=charOffset)low=middle+1;
            else high=middle-1;
        }
        return Math.max(0,high);
    }
}
