package com.nasmount.mobile;

/**
 * EPUB progress is based on the length of its ordered body text, NOT on
 * the number of XHTML spine chapters. A preface and a 30-page chapter
 * must not each contribute exactly 1/54th of the book.
 *
 * Old positions/bookmarks remain encoded as chapterIndex*10000+fraction.
 * Progress is derived from that representation without migrating or
 * deleting the user's reading history.
 */
final class EpubProgressMap {
    private final long[] cumulative;
    private final long total;
    EpubProgressMap(long[] lengths){
        if(lengths==null||lengths.length==0)
            throw new IllegalArgumentException("EPUB 본문 장이 없습니다.");
        cumulative=new long[lengths.length+1];
        for(int i=0;i<lengths.length;i++){
            long weight=Math.max(1L,lengths[i]);
            if(cumulative[i]>Long.MAX_VALUE-weight)
                throw new IllegalArgumentException("EPUB 전체 분량 범위 초과");
            cumulative[i+1]=cumulative[i]+weight;
        }
        total=cumulative[lengths.length];
    }
    int chapters(){return cumulative.length-1;}
    long totalWeight(){return total;}
    long chapterWeight(int index){
        int i=Math.max(0,Math.min(chapters()-1,index));
        return cumulative[i+1]-cumulative[i];
    }
    int permilleAt(int oldPosition){
        int chapter=Math.max(0,Math.min(chapters()-1,
                oldPosition/10000));
        int fraction=Math.max(0,Math.min(9999,
                oldPosition%10000));
        double read=cumulative[chapter]+
                (cumulative[chapter+1]-cumulative[chapter])*
                        (fraction/9999d);
        return Math.max(0,Math.min(1000,
                (int)Math.round(1000d*read/total)));
    }
    int percentAt(int oldPosition){
        return Math.max(0,Math.min(100,
                (int)Math.round(permilleAt(oldPosition)/10d)));
    }
    /** 0-1000 => legacy chapter + 0-9999 intra-chapter location. */
    int positionAt(int permille){
        int p=Math.max(0,Math.min(1000,permille));
        if(p==1000)return (chapters()-1)*10000+9999;
        double distance=total*(p/1000d);
        int chapter=0;
        while(chapter+1<chapters()&&
                distance>=cumulative[chapter+1])chapter++;
        long weight=cumulative[chapter+1]-cumulative[chapter];
        int fraction=(int)Math.floor((distance-cumulative[chapter])*
                9999d/weight);
        return chapter*10000+Math.max(0,Math.min(9999,fraction));
    }
    int chapterAt(int permille){
        return Math.max(0,Math.min(chapters()-1,
                positionAt(permille)/10000));
    }
}
