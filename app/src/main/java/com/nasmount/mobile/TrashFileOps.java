package com.nasmount.mobile;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/** Same-volume operations. Never follow symlinks when purging a directory. */
final class TrashFileOps {
    static final String PREFIX=".filehub-aio-trash-";
    static boolean reserved(String name){return name!=null&&name.startsWith(PREFIX);}
    static boolean expired(long deleted,long now,int days){
        return days>0&&deleted>0&&now>=deleted&&now-deleted>=days*86400000L;
    }
    static String slotId(String id)throws IOException{
        try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}
        catch(Exception e){throw new IOException("Invalid trash entry");}
    }
    static String safeRelative(String relative)throws IOException{
        if(relative==null||relative.isEmpty()||relative.startsWith("/")||relative.contains("\\"))
            throw new IOException("Invalid trash path");
        for(String p:relative.split("/",-1))
            if(p.isEmpty()||p.equals(".")||p.equals("..")||reserved(p)||p.indexOf(0)>=0)
                throw new IOException("Invalid trash path");
        return relative;
    }
    static File checked(File root,String relative)throws IOException{
        safeRelative(relative);
        File base=root.getCanonicalFile(),cursor=base;
        for(String part:relative.split("/")){
            cursor=new File(cursor,part);
            if(Files.isSymbolicLink(cursor.toPath()))throw new IOException("Symbolic link not supported");
        }
        if(!cursor.getCanonicalPath().startsWith(base.getPath()+File.separator))
            throw new IOException("Path outside storage");
        return cursor;
    }
    static void move(File source,File target)throws IOException{
        if(!Files.exists(source.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(source.toPath()))
            throw new IOException("Source missing or symbolic link");
        if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.getName());
        Files.move(source.toPath(),target.toPath());
    }
    static void purge(File item)throws IOException{
        if(!Files.exists(item.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(item.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path f,BasicFileAttributes attrs)throws IOException{
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                Files.delete(f);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{
                if(e!=null)throw e;Files.delete(d);return FileVisitResult.CONTINUE;
            }
        });
    }
    static long bytes(File file)throws IOException{
        final long[] total={0};final int[] seen={0};
        Files.walkFileTree(file.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{
                if(Thread.currentThread().isInterrupted()||++seen[0]>200000)throw new IOException("Size limit");
                if(!a.isSymbolicLink())total[0]=Math.addExact(total[0],a.size());
                return FileVisitResult.CONTINUE;
            }
        });return total[0];
    }
    private TrashFileOps(){}
}
