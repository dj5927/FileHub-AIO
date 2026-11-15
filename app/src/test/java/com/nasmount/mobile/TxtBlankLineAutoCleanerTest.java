package com.nasmount.mobile;

import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class TxtBlankLineAutoCleanerTest {
    private static String inflated(int lines) {
        StringBuilder text=new StringBuilder(lines*35);
        for(int i=0;i<lines;i++){
            text.append(i%71==0?"제"+i+"화 - 제목":"본문-"+i);
            if(i<lines-1)
                text.append(i%71==0?"\n\n\n\n\n":
                        i%3==0?"\n\n\n\n":"\n\n\n\n\n\n");
        }
        return text.toString();
    }

    @Test public void determinesPatternWithoutTouchingNormalParagraphs(){
        String original=inflated(400);
        TxtBlankLineAutoCleaner.Pattern result=
                TxtBlankLineAutoCleaner.detect(original);
        assertNotNull(result);
        assertEquals(4,result.singleBreakRun);
        assertEquals(1,result.keep(4));
        assertEquals(2,result.keep(5));
        assertEquals(2,result.keep(6));
        assertEquals(2,result.keep(2));
        assertEquals(8,result.keep(8));
        StringBuilder ordinary=new StringBuilder();
        for(int i=0;i<500;i++)ordinary.append("책 문장\n\n문단 끝\n");
        assertNull(TxtBlankLineAutoCleaner.detect(ordinary.toString()));
    }

    @Test public void doesNotGuessFromAmbiguousOrMixedSpacing(){
        StringBuilder allFour=new StringBuilder();
        StringBuilder mixed=new StringBuilder();
        for(int i=0;i<400;i++){
            allFour.append("본문-"+i+"\n\n\n\n");
            mixed.append("본문-"+i+(i%4==0?"\n":"\n\n\n\n\n\n"));
        }
        assertNull(TxtBlankLineAutoCleaner.detect(allFour.toString()));
        assertNull(TxtBlankLineAutoCleaner.detect(mixed.toString()));
    }

    @Test public void convertsOnlyTemporaryCacheAndMapsReadingPositions()throws Exception{
        String raw=inflated(3000);
        File cache=Files.createTempDirectory("txt-auto-clean-test").toFile();
        LargeTextBook staged=null,normal=null;
        try{
            staged=LargeTextBook.stage(new ByteArrayInputStream(
                    raw.getBytes(StandardCharsets.UTF_8)),cache);
            normal=staged.autoCleanBlankLines();
            assertNotSame(staged,normal);
            assertTrue(normal.length()<raw.length());
            TxtBlankLineAutoCleaner.OffsetMap map=normal.offsets();
            assertNotNull(map);
            assertEquals(raw.length(),map.rawLength());
            assertEquals(normal.length(),map.displayLength());
            assertTrue(map.changedRuns()>2500);
            String text=normal.slice(0,(int)normal.length());
            assertTrue(text.contains("제0화 - 제목\n\n본문-1\n\n본문-2"));
            assertFalse(text.contains("\n\n\n\n"));
            for(int i=1;i<3000;i+=17){
                String marker="본문-"+i;
                long rawPos=raw.indexOf(marker);
                long cleanPos=text.indexOf(marker);
                if(rawPos<0 || cleanPos<0)continue;
                assertEquals("raw to display "+i,cleanPos,map.toDisplay(rawPos));
                assertEquals("display to raw "+i,rawPos,map.toOriginal(cleanPos));
            }
            assertEquals(0,map.toDisplay(0));
            assertEquals(raw.length(),map.toOriginal(normal.length()));
        }finally{
            if(normal!=null)normal.close();
            if(staged!=null && normal==staged)staged.close();
            cache.delete();
        }
    }

    @Test public void keepsOrdinaryTxtCacheUnchanged()throws Exception{
        StringBuilder raw=new StringBuilder();
        for(int i=0;i<600;i++)raw.append("안녕하세요. "+i+"\n\n");
        File cache=Files.createTempDirectory("txt-standard-test").toFile();
        LargeTextBook book=null;
        try{
            book=LargeTextBook.stage(new ByteArrayInputStream(
                    raw.toString().getBytes(StandardCharsets.UTF_8)),cache);
            assertSame(book,book.autoCleanBlankLines());
            assertNull(book.offsets());
            assertEquals(raw.toString(),book.slice(0,(int)book.length()));
        }finally{
            if(book!=null)book.close();
            cache.delete();
        }
    }

    /** Optional real document test: never copies the user's novel to GitHub. */
    @Test public void exampleNovelIsCleanedWithoutChangingOriginalFile()throws Exception{
        String source=System.getenv("FILEHUB_TXT_EXAMPLE");
        Assume.assumeTrue(source!=null && !source.trim().isEmpty());
        File novel=new File(source);
        Assume.assumeTrue(novel.isFile());
        File cache=Files.createTempDirectory("txt-real-test").toFile();
        byte[] originalHash=MessageDigest.getInstance("SHA-256").digest(
                Files.readAllBytes(novel.toPath()));
        LargeTextBook original=null,cleaned=null;
        try(FileInputStream in=new FileInputStream(novel)){
            original=LargeTextBook.stage(in,cache);
            cleaned=original.autoCleanBlankLines();
            assertNotSame(original,cleaned);
            assertTrue(cleaned.offsets().changedRuns()>100_000);
            String start=cleaned.slice(0,2400);
            assertTrue(start.contains("\n\n"));
            assertFalse(start.contains("\n\n\n\n"));
            assertEquals(originalHash.length,MessageDigest.getInstance(
                    "SHA-256").digest(Files.readAllBytes(novel.toPath())).length);
            assertArrayEquals(originalHash,MessageDigest.getInstance(
                    "SHA-256").digest(Files.readAllBytes(novel.toPath())));
        }finally{
            if(cleaned!=null)cleaned.close();
            if(original!=null&&cleaned==original)original.close();
            cache.delete();
        }
    }
}
