package com.nasmount.mobile;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * Conservative, TXT-only automatic repair for uniformly inflated line feeds.
 * Detection is per-file; normal books and intentional paragraph breaks are
 * left untouched unless a strong and repeated global pattern is present.
 */
final class TxtBlankLineAutoCleaner {
    private TxtBlankLineAutoCleaner(){}

    static final class Pattern {
        final int singleBreakRun;
        Pattern(int singleBreakRun){this.singleBreakRun=singleBreakRun;}
        int keep(int run) {
            if(run==singleBreakRun)return 1;
            if(run==singleBreakRun+1 || run==singleBreakRun+2)return 2;
            // Never guess what less common spacing was intended to mean.
            return run;
        }
    }

    /** Only infer 3/5, 4/6, ... when nearly all separators fit the pattern. */
    static Pattern detect(String sample) {
        if(sample==null||sample.length()<1200)return null;
        int[] counts=new int[20];
        int total=0;
        for(int i=0;i<sample.length();) {
            if(sample.charAt(i)!='\n'){i++;continue;}
            int start=i;
            while(i<sample.length()&&sample.charAt(i)=='\n')i++;
            if(start==0 || i==sample.length())continue; // partial sample edges
            counts[Math.min(counts.length-1,i-start)]++;
            total++;
        }
        if(total<100)return null;
        for(int base=3;base<=6;base++){
            int normal=counts[base], paragraphs=counts[base+2];
            int middle=counts[base+1];
            if(normal<20||paragraphs<20)continue;
            if(normal+paragraphs<Math.ceil(total*0.90))continue;
            if(normal+middle+paragraphs<Math.ceil(total*0.97))continue;
            if(counts[1]+counts[2]>Math.floor(total*0.02))continue;
            return new Pattern(base);
        }
        return null;
    }

    /** Efficient monotone map for raw bookmark/reading offsets. */
    static final class OffsetMap {
        private long[] rawStarts=new long[128];
        private long[] rawEnds=new long[128];
        private long[] cleanEnds=new long[128];
        private long[] removedAfter=new long[128];
        private int count;
        private long rawLength,displayLength;

        void add(long rawStart,int run,int kept,long removedBefore){
            if(run<=kept)return;
            if(count==rawStarts.length){
                int next=Math.min(1_000_000,rawStarts.length*2);
                if(next<=count)throw new IllegalStateException("TXT 공백 위치 제한 초과");
                rawStarts=Arrays.copyOf(rawStarts,next);
                rawEnds=Arrays.copyOf(rawEnds,next);
                cleanEnds=Arrays.copyOf(cleanEnds,next);
                removedAfter=Arrays.copyOf(removedAfter,next);
            }
            rawStarts[count]=rawStart;
            rawEnds[count]=rawStart+run;
            cleanEnds[count]=rawStart+kept-removedBefore;
            removedAfter[count]=removedBefore+(run-kept);
            count++;
        }
        void finish(long raw,long clean){rawLength=raw;displayLength=clean;}
        long rawLength(){return rawLength;}
        long displayLength(){return displayLength;}
        boolean changed(){return count>0;}
        int changedRuns(){return count;}

        long toOriginal(long displayOffset) {
            long pos=Math.max(0,Math.min(displayLength,displayOffset));
            int low=0,high=count;
            while(low<high){
                int middle=(low+high)>>>1;
                if(cleanEnds[middle]<=pos)low=middle+1;
                else high=middle;
            }
            long removed=low==0?0:removedAfter[low-1];
            return Math.min(rawLength,pos+removed);
        }

        long toDisplay(long originalOffset){
            long pos=Math.max(0,Math.min(rawLength,originalOffset));
            int low=0,high=count;
            while(low<high){
                int middle=(low+high)>>>1;
                if(rawEnds[middle]<=pos)low=middle+1;
                else high=middle;
            }
            if(low>=count)return pos-(count==0?0:removedAfter[count-1]);
            long previous=low==0?0:removedAfter[low-1];
            long retainedEnd=cleanEnds[low]+previous;
            if(pos<=retainedEnd)return pos-previous;
            return cleanEnds[low];
        }
    }

    /** Streaming writer: a 100MB novel does not need a 100MB String. */
    static final class WriterState {
        private final Pattern pattern;
        private final OffsetMap offsets=new OffsetMap();
        private long original,written,removed;
        private int pendingNewlines;
        private final StringBuilder section=new StringBuilder(16384);
        WriterState(Pattern pattern){this.pattern=pattern;}
        OffsetMap offsets(){return offsets;}
        long written(){return written;}

        void append(String characters,Writer sink)throws IOException {
            for(int i=0;i<characters.length();i++){
                char ch=characters.charAt(i);
                if(ch=='\n'){
                    if(section.length()>0)flushText(sink);
                    pendingNewlines++;
                }else{
                    flushNewlines(sink);
                    section.append(ch);
                    if(section.length()>=16384)flushText(sink);
                }
                original++;
            }
        }
        private void flushText(Writer sink)throws IOException{
            if(section.length()==0)return;
            sink.write(section.toString());
            written+=section.length();
            section.setLength(0);
        }
        private void flushNewlines(Writer sink)throws IOException {
            if(pendingNewlines==0)return;
            int originalRun=pendingNewlines;
            int retained=pattern.keep(originalRun);
            long runStart=original-originalRun;
            offsets.add(runStart,originalRun,retained,removed);
            for(int i=0;i<retained;i++)sink.write('\n');
            written+=retained;
            removed+=originalRun-retained;
            pendingNewlines=0;
        }
        void finish(Writer sink)throws IOException {
            flushText(sink);
            flushNewlines(sink);
            offsets.finish(original,written);
        }
    }
}
