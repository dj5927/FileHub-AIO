package com.nasmount.mobile;

import java.util.*;

/** One immutable directory revision, with opaque row identifiers. */
final class ExplorerPageIndex<T> {
    private final String revision;
    private final List<T> rows;
    ExplorerPageIndex(String revision,List<T> rows) {
        this.revision=Objects.requireNonNull(revision);
        this.rows=Collections.unmodifiableList(new ArrayList<>(rows));
    }
    String revision(){return revision;}
    int size(){return rows.size();}
    String id(int index){return revision+":"+index;}
    int resolve(String id){
        if(id==null||!id.startsWith(revision+":"))return -1;
        try{
            int i=Integer.parseInt(id.substring(revision.length()+1));
            return i>=0&&i<rows.size()&&id.equals(id(i))?i:-1;
        }catch(NumberFormatException e){return -1;}
    }
    T get(int index){return rows.get(index);}
    int end(int offset){return LargeFolderPagePolicy.end(rows.size(),offset);}
}
