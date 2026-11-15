package com.nasmount.mobile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.UUID;

/**
 * File-backed, normalized TXT source. A book is converted a piece at a time
 * into UTF-16BE, giving O(1) random access by character position without
 * putting a multi-megabyte novel in a TextView or the Java heap.
 */
final class LargeTextBook implements Closeable {
    private static final int PROBE_BYTES=32768;
    private final File file;
    private final RandomAccessFile input;
    private final long chars;

    private LargeTextBook(File file,long chars)throws IOException {
        this.file=file;
        this.chars=chars;
        this.input=new RandomAccessFile(file,"r");
    }
    long length(){return chars;}

    static LargeTextBook stage(InputStream source,File cacheDir)throws Exception{
        if(!cacheDir.isDirectory()&&!cacheDir.mkdirs())
            throw new IOException("텍스트 독서 캐시를 만들지 못했습니다.");
        File out=new File(cacheDir,"txt_"+UUID.randomUUID()+".utf16");
        try{
            BufferedInputStream buffered=new BufferedInputStream(source,65536);
            buffered.mark(PROBE_BYTES+16);
            byte[] sample=new byte[PROBE_BYTES];int size=0;
            while(size<sample.length){
                int n=buffered.read(sample,size,sample.length-size);
                if(n<0)break;
                if(n==0)continue;
                size+=n;
            }
            buffered.reset();
            Charset charset=detect(sample,size);
            long written=0;
            try(Reader reader=new BufferedReader(
                        new InputStreamReader(buffered,charset),65536);
                Writer writer=new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(out),StandardCharsets.UTF_16BE),
                        65536)){
                char[] block=new char[16384];
                int n;boolean leading=true,pendingCR=false;
                while((n=reader.read(block))!=-1){
                    if(Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("텍스트 읽기가 중단됐습니다.");
                    char[] clean=new char[n+2];int count=0;
                    for(int i=0;i<n;i++){
                        char c=block[i];
                        if(leading){
                            leading=false;
                            if(c=='\ufeff')continue;
                        }
                        if(pendingCR){
                            clean[count++]='\n';written++;
                            pendingCR=false;
                            if(c=='\n')continue;
                        }
                        if(c=='\r')pendingCR=true;
                        else if(c!='\0'){clean[count++]=c;written++;}
                    }
                    writer.write(clean,0,count);
                }
                if(pendingCR){writer.write('\n');written++;}
            }
            if(written==0)throw new IOException("읽을 수 있는 텍스트가 없습니다.");
            return new LargeTextBook(out,written);
        }catch(Exception error){
            out.delete();
            throw error;
        }
    }
    static Charset detect(byte[] sample,int size) {
        if(size>=2&&(sample[0]&255)==255&&(sample[1]&255)==254)
            return StandardCharsets.UTF_16LE;
        if(size>=2&&(sample[0]&255)==254&&(sample[1]&255)==255)
            return StandardCharsets.UTF_16BE;
        if(size>=3&&(sample[0]&255)==239&&(sample[1]&255)==187&&
                (sample[2]&255)==191)return StandardCharsets.UTF_8;
        try{
            CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            // The final probed bytes could be an incomplete UTF-8 sequence.
            CoderResult result=decoder.decode(ByteBuffer.wrap(sample,0,size),
                    java.nio.CharBuffer.allocate(size),false);
            if(result.isError())result.throwException();
            return StandardCharsets.UTF_8;
        }catch(Exception invalidUtf8){
            try{return Charset.forName("MS949");}
            catch(Exception unsupported){return Charset.forName("EUC-KR");}
        }
    }
    synchronized String slice(long start,int requested)throws IOException{
        if(start<0||start>chars)throw new IOException("잘못된 텍스트 위치");
        int count=(int)Math.min(Math.max(0,requested),chars-start);
        if(count==0)return "";
        byte[] bytes=new byte[count*2];
        input.seek(start*2);
        input.readFully(bytes);
        return new String(bytes,StandardCharsets.UTF_16BE);
    }
    @Override public synchronized void close()throws IOException{
        try{input.close();}finally{file.delete();}
    }
}
