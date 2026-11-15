package com.nasmount.mobile;

/**
 * Prefer an existing Korean paragraph/sentence boundary near the bottom
 * of a page. Original characters are never inserted/deleted/reordered:
 * choosing end for page N also chooses start for N+1.
 *
 * The scan is restricted to the LAST two visible lines by BookPaginator;
 * never drag an entire paragraph to the next page and create a blank sheet.
 */
final class KoreanPageBoundary {
    private KoreanPageBoundary(){}
    static int choose(CharSequence text,int pageStart,int measuredEnd,
                      int lastTwoLineStart){
        if(text==null||measuredEnd<=pageStart+1)return measuredEnd;
        final int begin=Math.max(pageStart+1,Math.max(0,lastTwoLineStart));
        final int end=Math.min(text.length(),measuredEnd);
        if(begin>=end)return measuredEnd;
        int lastParagraph=-1;
        int lastSentence=-1;
        for(int i=begin;i<end;i++){
            char c=text.charAt(i);
            if(c=='\n'){
                // A single existing hard newline is a real paragraph
                // break in TXT, not a visual line wrap. Keep it intact.
                lastParagraph=i+1;
                continue;
            }
            if(!isTerminal(c))continue;
            if(c=='.'&&i>0&&i+1<text.length()&&
                    Character.isDigit(text.charAt(i-1))&&
                    Character.isDigit(text.charAt(i+1)))continue;
            // Do not treat an English acronym (e.g. U.S.) as a sentence.
            if(c=='.'&&i>0&&i+1<text.length()&&
                    isAsciiLetter(text.charAt(i-1))&&
                    isAsciiLetter(text.charAt(i+1)))continue;
            // "U.S." / "A.B." are not Korean sentence-ending periods.
            if(c=='.'&&i>=3&&text.charAt(i-2)=='.'&&
                    isAsciiLetter(text.charAt(i-3))&&
                    isAsciiLetter(text.charAt(i-1)))continue;
            int candidate=i+1;
            while(candidate<end&&isClosing(text.charAt(candidate)))
                candidate++;
            while(candidate<end&&(text.charAt(candidate)==' '||
                    text.charAt(candidate)=='\t'))candidate++;
            if(candidate>begin&&candidate<end)
                lastSentence=candidate;
        }
        // Choose the closest existing meaningful boundary. Sentence wins
        // when more recent; paragraph wins on equal position.
        int chosen=Math.max(lastParagraph,lastSentence);
        if(chosen<=pageStart||chosen>=end)return measuredEnd;
        if(chosen>0&&chosen<text.length()&&
                Character.isLowSurrogate(text.charAt(chosen))&&
                Character.isHighSurrogate(text.charAt(chosen-1)))
            return measuredEnd;
        return chosen;
    }
    private static boolean isTerminal(char c){
        return c=='.'||c=='?'||c=='!'||c=='…'||
                c=='。'||c=='？'||c=='！';
    }
    private static boolean isClosing(char c){
        return c=='"'||c=='\''||c=='”'||c=='’'||c=='」'||
                c=='』'||c==')'||c=='）'||c=='】';
    }
    private static boolean isAsciiLetter(char c){
        return (c>='A'&&c<='Z')||(c>='a'&&c<='z');
    }
}
