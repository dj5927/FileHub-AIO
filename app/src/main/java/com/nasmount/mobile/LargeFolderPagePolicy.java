package com.nasmount.mobile;

/** Bounded row inflation prevents a giant folder from blocking the UI. */
final class LargeFolderPagePolicy {
    static final int ROWS_PER_PAGE=60;
    private LargeFolderPagePolicy(){}
    static int end(int total,int offset){
        if(total<0||offset<0||offset>total)
            throw new IllegalArgumentException("Invalid folder page");
        return (int)Math.min((long)total,(long)offset+ROWS_PER_PAGE);
    }
}
