package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical reader pages built from original archive images. A landscape image
 * may contain two printed pages. On a narrow display those halves turn as
 * separate logical pages; on a wide display they can reunite as a spread.
 */
final class ComicPageLayout {
    static final int WHOLE=0,LEFT=1,RIGHT=2;
    static final int AUTO=0,NEVER=1,AGGRESSIVE=2;
    static final class Page {
        final int sourceIndex;
        final int slice;
        Page(int sourceIndex,int slice){
            this.sourceIndex=sourceIndex;this.slice=slice;
        }
    }
    private ComicPageLayout(){}
    static boolean shouldSplit(int width,int height,int mode){
        if(mode==NEVER||height<=0||width<=0)return false;
        // For true two-page scans (~2:1) require only clear landscape aspect.
        // The optional aggressive mode handles tightly cropped scans.
        return (float)width/height >= (mode==AGGRESSIVE?1.13f:1.38f);
    }
    static List<Page> create(List<int[]> dimensions,int mode,boolean rtl){
        List<Page> result=new ArrayList<>();
        for(int i=0;i<dimensions.size();i++){
            int[] dimensionsOfPage=dimensions.get(i);
            if(shouldSplit(dimensionsOfPage[0],dimensionsOfPage[1],mode)){
                result.add(new Page(i,rtl?RIGHT:LEFT));
                result.add(new Page(i,rtl?LEFT:RIGHT));
            }else result.add(new Page(i,WHOLE));
        }
        return result;
    }
    static int firstPageOfSource(List<Page> pages,int source) {
        for(int i=0;i<pages.size();i++)
            if(pages.get(i).sourceIndex>=source)return i;
        return Math.max(0,pages.size()-1);
    }
    static boolean twoHalvesTogether(List<Page> pages,int first){
        return first>=0 && first+1<pages.size() &&
                pages.get(first).sourceIndex==pages.get(first+1).sourceIndex &&
                pages.get(first).slice!=WHOLE &&
                pages.get(first+1).slice!=WHOLE;
    }
    /** Never pair half a scan with an unrelated image on Fold. */
    static List<Integer> spreadStarts(List<Page> pages){
        List<Integer> out=new ArrayList<>();
        for(int i=0;i<pages.size();){
            out.add(i);
            if(twoHalvesTogether(pages,i))i+=2;
            else if(i+1<pages.size() && pages.get(i).slice==WHOLE &&
                    pages.get(i+1).slice==WHOLE)i+=2;
            else i++;
        }
        return out;
    }
    static int spreadStart(List<Integer> groups,int logicalIndex) {
        int chosen=0;
        for(int start:groups){
            if(start>logicalIndex)break;
            chosen=start;
        }
        return chosen;
    }
    static int spreadEnd(List<Integer> groups,int start,int total){
        for(int i=0;i<groups.size();i++){
            if(groups.get(i)==start)
                return i+1<groups.size()?groups.get(i+1):total;
        }
        return Math.min(total,start+1);
    }
}
