package com.nasmount.mobile;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class LargeTextBookTest {
    @Test public void moreThan12MBCanBeReadWithoutAllocatingEntireBook()throws Exception {
        File cache=new File(System.getProperty("java.io.tmpdir"),"nas-text-test");
        final long total=13L*1024*1024+123;
        InputStream input=new InputStream(){
            long count;
            @Override public int read(){
                return count++<total?(int)'x':-1;
            }
            @Override public int read(byte[] bytes,int off,int len){
                if(count>=total)return -1;
                int n=(int)Math.min(len,total-count);
                java.util.Arrays.fill(bytes,off,off+n,(byte)'x');
                count+=n;return n;
            }
        };
        try(LargeTextBook book=LargeTextBook.stage(input,cache)){
            assertEquals(total,book.length());
            assertEquals("xxxxx",book.slice(total-5,5));
            assertEquals(24000,book.slice(9000000,24000).length());
        }
    }
    @Test public void koreanEncodingAndLineEndingAreNormalized()throws Exception {
        File cache=new File(System.getProperty("java.io.tmpdir"),"nas-text-test");
        String content="한글\r\n소설\r마지막";
        try(LargeTextBook book=LargeTextBook.stage(
                new ByteArrayInputStream(content.getBytes("MS949")),cache)){
            assertEquals("한글\n소설\n마지막",
                    book.slice(0,(int)book.length()));
        }
    }
}
