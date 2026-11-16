package com.nasmount.mobile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Bounded DFS with a pluggable folder backend (File, DocumentFile, NAS).
 * Never call on Android's main thread. Each result retains its native node
 * so clicking a nested hit can open the real item without fake paths.
 */
final class FolderRecursiveSearch {
    static final int MAX_MATCHES=300;
    static final int MAX_ENTRIES=30000;
    static final int MAX_FOLDERS=4000;
    static final int MAX_DEPTH=32;
    private FolderRecursiveSearch(){}

    interface Adapter<N>{
        List<N> list(N folder)throws Exception;
        boolean directory(N node);
        String name(N node);
        String id(N node);
    }
    static final class Hit<N>{
        final N node;
        final String path;
        Hit(N node,String path){this.node=node;this.path=path;}
    }
    static final class Result<N>{
        final List<Hit<N>> hits=new ArrayList<>();
        int entries,folders,errors;
        boolean limited,cancelled;
    }
    private static final class Pending<N>{
        final N folder;
        final String relative;
        final int depth;
        Pending(N folder,String relative,int depth){
            this.folder=folder;this.relative=relative;this.depth=depth;
        }
    }
    static <N> Result<N> find(N root,Adapter<N> adapter,String query,
                             boolean showHidden,BooleanSupplier cancelled){
        Result<N> out=new Result<>();
        if(root==null||query==null||query.trim().isEmpty())return out;
        ArrayDeque<Pending<N>> remaining=new ArrayDeque<>();
        Set<String> visited=new HashSet<>();
        remaining.addLast(new Pending<>(root,"",0));
        while(!remaining.isEmpty()){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){
                out.cancelled=true;return out;
            }
            if(out.folders>=MAX_FOLDERS||out.entries>=MAX_ENTRIES){
                out.limited=true;break;
            }
            Pending<N> current=remaining.removeLast();
            String id=adapter.id(current.folder);
            if(id==null||!visited.add(id))continue;
            out.folders++;
            List<N> children;
            try{children=adapter.list(current.folder);}
            catch(Exception failure){out.errors++;continue;}
            if(children==null)continue;
            for(N child:children){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()){
                    out.cancelled=true;return out;
                }
                if(++out.entries>MAX_ENTRIES){out.limited=true;break;}
                String name=adapter.name(child);
                if(name==null||name.isEmpty()||(!showHidden&&name.startsWith(".")))
                    continue;
                String relative=current.relative+name;
                if(FileViewOptions.matches(name,query)){
                    out.hits.add(new Hit<>(child,relative));
                    if(out.hits.size()>=MAX_MATCHES){
                        out.limited=true;break;
                    }
                }
                if(adapter.directory(child)){
                    if(current.depth+1>MAX_DEPTH){
                        out.limited=true;continue;
                    }
                    remaining.addLast(new Pending<>(
                            child,relative+"/",current.depth+1));
                }
            }
            if(out.limited&&
                    (out.entries>=MAX_ENTRIES||out.hits.size()>=MAX_MATCHES))
                break;
        }
        return out;
    }
}
