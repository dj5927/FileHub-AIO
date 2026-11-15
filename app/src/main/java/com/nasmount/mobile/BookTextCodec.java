package com.nasmount.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

/** UTF-8/UTF-16 and Korean legacy TXT loading, with bounded input. */
final class BookTextCodec {
    private BookTextCodec(){}
    static String decode(byte[] bytes)throws Exception {
        if(bytes.length==0)throw new IOException("텍스트 파일이 비어 있습니다.");
        String text;
        if(bytes.length>=3&&(bytes[0]&255)==0xef&&(bytes[1]&255)==0xbb&&
                (bytes[2]&255)==0xbf){
            text=new String(bytes,3,bytes.length-3,StandardCharsets.UTF_8);
        }else if(bytes.length>=2&&(bytes[0]&255)==0xff&&(bytes[1]&255)==0xfe){
            text=new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16LE);
        }else if(bytes.length>=2&&(bytes[0]&255)==0xfe&&(bytes[1]&255)==0xff){
            text=new String(bytes,2,bytes.length-2,StandardCharsets.UTF_16BE);
        }else{
            try{
                text=StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            }catch(CharacterCodingException invalidUtf8){
                Charset korean;
                try{korean=Charset.forName("MS949");}
                catch(Exception unsupported){korean=Charset.forName("EUC-KR");}
                text=korean.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            }
        }
        text=text.replace("\r\n","\n").replace('\r','\n')
                .replace("\u0000","");
        if(text.isEmpty())throw new IOException("읽을 수 있는 본문 내용이 없습니다.");
        return text;
    }
    static String load(InputStream input,long maxBytes)throws Exception {
        try(ByteArrayOutputStream memory=new ByteArrayOutputStream()){
            byte[] block=new byte[65536];int n;long size=0;
            while((n=input.read(block))>=0){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(n==0)continue;
                size+=n;
                if(size>maxBytes)throw new IOException("텍스트 읽기 예산을 초과했습니다.");
                memory.write(block,0,n);
            }
            return decode(memory.toByteArray());
        }
    }
}
