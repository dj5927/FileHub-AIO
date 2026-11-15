package com.nasmount.mobile;

import java.util.List;
import java.util.Set;

/** Explicit bookmark-like folder roles; matching is storage/account scoped.
 * Only directly marked folders get list badges. Descendants still inherit
 * the behavior through their existing video/reader matching engines. */
final class FolderPurposePolicy {
    static final int VIDEO=1;
    static final int READING=2;
    private FolderPurposePolicy(){}

    static int labels(Set<String> markedVideo,
                      List<ReaderFolders.Scope> bookFolders,
                      String kind,String owner,String path,String videoScope) {
        int result=0;
        if(videoScope!=null&&markedVideo!=null&&
                markedVideo.contains(FolderScopeFilter.normalize(videoScope)))
            result|=VIDEO;
        if(bookFolders!=null) {
            String safeOwner=owner==null?"":owner;
            for(ReaderFolders.Scope saved:bookFolders) {
                if(saved.kind.equals(kind)&&saved.owner.equals(safeOwner)&&
                        saved.path.equals(ReaderFolders.normalize(kind,path))) {
                    result|=READING;
                    break;
                }
            }
        }
        return result;
    }
}
