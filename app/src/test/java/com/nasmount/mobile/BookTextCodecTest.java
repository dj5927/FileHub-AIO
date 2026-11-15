package com.nasmount.mobile;

import org.junit.Test;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class BookTextCodecTest {
    @Test public void utf8BOMAndKorean()throws Exception{
        byte[] korean="책처럼 읽기".getBytes(StandardCharsets.UTF_8);
        byte[] bom=new byte[korean.length+3];
        bom[0]=(byte)0xef;bom[1]=(byte)0xbb;bom[2]=(byte)0xbf;
        System.arraycopy(korean,0,bom,3,korean.length);
        assertEquals("책처럼 읽기",BookTextCodec.decode(bom));
    }
    @Test public void windowsKoreanTxt()throws Exception{
        byte[] bytes="한글 소설 가나다".getBytes(Charset.forName("MS949"));
        assertEquals("한글 소설 가나다",BookTextCodec.decode(bytes));
    }
    @Test public void utf16AndLineBreakNormalization()throws Exception{
        byte[] original="줄1\r\n줄2".getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes=new byte[original.length+2];
        bytes[0]=(byte)255;bytes[1]=(byte)254;
        System.arraycopy(original,0,bytes,2,original.length);
        assertEquals("줄1\n줄2",BookTextCodec.decode(bytes));
    }
    @Test public void progressOffsetBinaryLookup() {
        List<Integer> starts=java.util.Arrays.asList(0,50,120,450);
        assertEquals(0,BookPaginator.pageFor(starts,0));
        assertEquals(1,BookPaginator.pageFor(starts,68));
        assertEquals(3,BookPaginator.pageFor(starts,500));
    }
}
