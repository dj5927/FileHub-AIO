package com.nasmount.mobile;

import android.graphics.BitmapFactory;
import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Comic page index. Opens images one-by-one *inside* ZIP/7z/RAR without
 * extracting the entire archive. For remote files the compressed archive
 * is staged once, not its unpacked contents.
 */
final class ComicArchive {
    static final long MAX_PAGE_BYTES=48L*1024*1024;
    static final int MAX_ENTRIES=20000;
    private ComicArchive(){}

    static boolean supports(String filename){
        if(filename==null)return false;
        String n=filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip")||n.endsWith(".cbz")||
                n.endsWith(".7z")||n.endsWith(".cb7")||
                n.endsWith(".rar")||n.endsWith(".cbr");
    }
    static boolean image(String filename){
        if(filename==null)return false;
        String n=filename.toLowerCase(Locale.ROOT);
        return n.endsWith(".png")||n.endsWith(".jpg")||
                n.endsWith(".jpeg")||n.endsWith(".webp")||
                n.endsWith(".bmp")||n.endsWith(".gif");
    }
    static boolean isSafePage(String name){
        if(!image(name)||name==null||name.length()>1024)return false;
        String normalized=name.replace('\\','/');
        if(normalized.startsWith("/")||normalized.startsWith("//")||
                normalized.matches("(?i)^[a-z]:.*") ||
                normalized.indexOf('\0')>=0)return false;
        for(String component:normalized.split("/"))
            if(component.equals(".")||component.equals("..")||
                    component.equalsIgnoreCase("__MACOSX")||
                    component.length()>255)return false;
        return true;
    }
    static int naturalCompare(String left,String right){
        int a=0,b=0;
        while(a<left.length()&&b<right.length()){
            char x=left.charAt(a),y=right.charAt(b);
            if(Character.isDigit(x)&&Character.isDigit(y)){
                int sa=a,sb=b;
                while(a<left.length()&&Character.isDigit(left.charAt(a)))a++;
                while(b<right.length()&&Character.isDigit(right.charAt(b)))b++;
                String na=left.substring(sa,a).replaceFirst("^0+(?!$)","");
                String nb=right.substring(sb,b).replaceFirst("^0+(?!$)","");
                if(na.length()!=nb.length())return Integer.compare(na.length(),nb.length());
                int c=na.compareTo(nb);
                if(c!=0)return c;
                continue;
            }
            int c=Character.compare(Character.toLowerCase(x),Character.toLowerCase(y));
            if(c!=0)return c;
            a++;b++;
        }
        return Integer.compare(left.length(),right.length());
    }
    static List<String> index(File archive,String name)throws Exception {
        if(archive==null||!archive.isFile()||
                archive.length()>ReaderSource.MAX_ARCHIVE)
            throw new IOException("압축 파일이 없거나 1.5GB 제한을 넘었습니다.");
        List<String> pages=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        String type=name.toLowerCase(Locale.ROOT);
        if(type.endsWith(".zip")||type.endsWith(".cbz")){
            try(ZipFile zip=new ZipFile(archive)){
                Enumeration<? extends ZipEntry> entries=zip.entries();
                int total=0;
                while(entries.hasMoreElements()){
                    if(++total>MAX_ENTRIES)throw new IOException("압축 항목 수가 너무 많습니다.");
                    ZipEntry e=entries.nextElement();
                    if(!e.isDirectory() && isSafePage(e.getName()) &&
                            e.getSize()<=MAX_PAGE_BYTES && seen.add(e.getName()))
                        pages.add(e.getName());
                }
            }
        }else if(type.endsWith(".7z")||type.endsWith(".cb7")){
            try(SevenZFile seven=SevenZFile.builder().setFile(archive).get()){
                SevenZArchiveEntry e;int count=0;
                while((e=seven.getNextEntry())!=null){
                    if(++count>MAX_ENTRIES)throw new IOException("압축 항목 수가 너무 많습니다.");
                    if(!e.isDirectory()&&!e.isAntiItem()&&
                            isSafePage(e.getName())&&e.getSize()<=MAX_PAGE_BYTES&&
                            seen.add(e.getName()))pages.add(e.getName());
                }
            }
        }else if(type.endsWith(".rar")||type.endsWith(".cbr")){
            try(Archive rar=new Archive(archive)){
                if(rar.isEncrypted()||rar.isPasswordProtected())
                    throw new IOException("암호화된 RAR은 지원하지 않습니다.");
                FileHeader h;int count=0;
                while((h=rar.nextFileHeader())!=null){
                    if(++count>MAX_ENTRIES)throw new IOException("압축 항목 수가 너무 많습니다.");
                    if(!h.isDirectory()&&isSafePage(h.getFileName())&&
                            h.getUnpSize()<=MAX_PAGE_BYTES &&
                            seen.add(h.getFileName()))pages.add(h.getFileName());
                }
            }
        }else throw new IOException("지원되지 않는 만화책 압축 형식");
        pages.sort(ComicArchive::naturalCompare);
        if(pages.isEmpty())
            throw new IOException(L10n.t("압축파일 안에 읽을 수 있는 그림이 없습니다."));
        return pages;
    }

    /** Detect double-page scans from metadata; no full bitmap decode. */
    static List<int[]> dimensions(File archive,String filename,List<String> pages,
                                  File cache) throws Exception {
        List<int[]> result=new ArrayList<>(pages.size());
        String lower=filename.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".zip")||lower.endsWith(".cbz")){
            try(ZipFile zip=new ZipFile(archive)){
                for(String item:pages){
                    ZipEntry entry=zip.getEntry(item);
                    BitmapFactory.Options options=new BitmapFactory.Options();
                    options.inJustDecodeBounds=true;
                    if(entry!=null && entry.getSize()<=MAX_PAGE_BYTES){
                        try(InputStream in=zip.getInputStream(entry)){
                            BitmapFactory.decodeStream(in,null,options);
                        }catch(Exception ignored){}
                    }
                    result.add(new int[]{options.outWidth,options.outHeight});
                }
            }
        }else{
            // Enumerate the archive only once for RAR/7z rather than
            // reopening and decompressing every previous page O(n^2) times.
            Map<String,Integer> index=new HashMap<>();
            for(int i=0;i<pages.size();i++){
                result.add(new int[]{0,0});
                index.put(pages.get(i),i);
            }
            if(lower.endsWith(".7z")||lower.endsWith(".cb7")){
                try(SevenZFile seven=SevenZFile.builder().setFile(archive).get()){
                    SevenZArchiveEntry entry;
                    while((entry=seven.getNextEntry())!=null){
                        if(Thread.currentThread().isInterrupted())
                            throw new InterruptedIOException();
                        Integer at=index.get(entry.getName());
                        if(at==null||entry.isDirectory())continue;
                        BitmapFactory.Options options=new BitmapFactory.Options();
                        options.inJustDecodeBounds=true;
                        try(InputStream in=new BufferedInputStream(new InputStream(){
                            @Override public int read()throws IOException{
                                return seven.read();
                            }
                            @Override public int read(byte[] data,int off,int len)
                                    throws IOException{
                                return seven.read(data,off,len);
                            }
                        })){
                            BitmapFactory.decodeStream(in,null,options);
                        }catch(Exception invalid){}
                        result.set(at,new int[]{options.outWidth,options.outHeight});
                    }
                }
            }else if(lower.endsWith(".rar")||lower.endsWith(".cbr")){
                try(Archive rar=new Archive(archive)){
                    FileHeader header;
                    while((header=rar.nextFileHeader())!=null){
                        if(Thread.currentThread().isInterrupted())
                            throw new InterruptedIOException();
                        Integer at=index.get(header.getFileName());
                        if(at==null||header.isDirectory())continue;
                        BitmapFactory.Options options=new BitmapFactory.Options();
                        options.inJustDecodeBounds=true;
                        try(InputStream in=rar.getInputStream(header)){
                            if(in!=null)BitmapFactory.decodeStream(in,null,options);
                        }catch(Exception invalid){}
                        result.set(at,new int[]{options.outWidth,options.outHeight});
                    }
                }
            }
        }
        return result;
    }

    private static void bounded(InputStream src,File output)throws IOException{
        try(OutputStream dst=new BufferedOutputStream(new FileOutputStream(output))){
            byte[] bytes=new byte[65536];long count=0;int n;
            while((n=src.read(bytes))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(n==0)continue;
                count+=n;
                if(count>MAX_PAGE_BYTES)throw new IOException("만화 이미지가 48MB를 넘었습니다.");
                dst.write(bytes,0,n);
            }
            if(count==0)throw new IOException("비어 있는 이미지 항목입니다.");
        }catch(IOException e){output.delete();throw e;}
    }
    static void extractPage(File archive,String filename,String path,File output)
            throws Exception {
        if(!isSafePage(path))throw new IOException("안전하지 않은 압축 항목 경로");
        String type=filename.toLowerCase(Locale.ROOT);
        if(type.endsWith(".zip")||type.endsWith(".cbz")){
            try(ZipFile zip=new ZipFile(archive)){
                ZipEntry entry=zip.getEntry(path);
                if(entry==null||entry.isDirectory())throw new IOException(L10n.t("페이지를 찾지 못했습니다."));
                if(entry.getSize()>MAX_PAGE_BYTES)throw new IOException(L10n.t("이미지 크기 제한 초과"));
                try(InputStream in=zip.getInputStream(entry)){bounded(in,output);}
            }
        }else if(type.endsWith(".7z")||type.endsWith(".cb7")){
            try(SevenZFile seven=SevenZFile.builder().setFile(archive).get()){
                SevenZArchiveEntry entry;
                while((entry=seven.getNextEntry())!=null){
                    if(!path.equals(entry.getName()))continue;
                    if(entry.isDirectory())throw new IOException("페이지가 폴더입니다.");
                    if(entry.getSize()>MAX_PAGE_BYTES)throw new IOException(L10n.t("이미지 크기 제한 초과"));
                    try(OutputStream dst=new BufferedOutputStream(new FileOutputStream(output))){
                        long count=0;byte[] block=new byte[65536];int len;
                        while((len=seven.read(block))!=-1){
                            count+=len;
                            if(count>MAX_PAGE_BYTES)throw new IOException(L10n.t("이미지 크기 제한 초과"));
                            dst.write(block,0,len);
                        }
                    }catch(Exception e){output.delete();throw e;}
                    return;
                }
                throw new IOException(L10n.t("페이지가 압축파일 안에 없습니다."));
            }
        }else if(type.endsWith(".rar")||type.endsWith(".cbr")){
            try(Archive rar=new Archive(archive)){
                FileHeader header;
                while((header=rar.nextFileHeader())!=null){
                    if(!path.equals(header.getFileName()))continue;
                    if(header.getUnpSize()>MAX_PAGE_BYTES)
                        throw new IOException(L10n.t("이미지 크기 제한 초과"));
                    try(InputStream in=rar.getInputStream(header)){
                        if(in==null)throw new IOException("RAR 이미지 읽기 실패");
                        bounded(in,output);
                    }
                    return;
                }
                throw new IOException(L10n.t("페이지가 압축파일 안에 없습니다."));
            }
        }else throw new IOException(L10n.t("지원되지 않는 압축 형식입니다."));
    }
}
