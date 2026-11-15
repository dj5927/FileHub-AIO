package com.nasmount.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A virtual shelf of explicitly designated video, comic or text/EPUB folders.
 * It uses the EXISTING folder-role preferences, rather than inventing a
 * second catalog that could drift out of sync with folder badges and readers.
 */
final class AssignedFolderCatalog {
    static final String VIDEO="video";
    static final String COMIC=ReaderFolders.COMIC;
    static final String TEXT=ReaderFolders.TEXT;
    static final class Folder {
        final String mode,kind,owner,path;
        Folder(String mode,String kind,String owner,String path){
            this.mode=mode;
            this.kind=kind;
            this.owner=owner==null?"":owner;
            this.path=path;
        }
        String key(){return kind+"|"+owner+"|"+path;}
        String name(){
            String normalized=path==null?"":path.replace('\\','/');
            while(normalized.length()>1&&normalized.endsWith("/"))
                normalized=normalized.substring(0,normalized.length()-1);
            int slash=normalized.lastIndexOf('/');
            int colon=normalized.lastIndexOf(':');
            int start=Math.max(slash,colon);
            String leaf=start>=0?normalized.substring(start+1):normalized;
            return leaf.isEmpty()?"최상위 폴더":leaf;
        }
    }
    private AssignedFolderCatalog(){}

    /** Video policy contains local:, remote:, storage: and SAF fallbacks. */
    static Folder fromVideoScope(String scope){
        if(scope==null)return null;
        String cleaned=FolderScopeFilter.normalize(scope);
        if(cleaned.equals("local:"))
            return new Folder(VIDEO,"local","","/");
        if(cleaned.startsWith("local:/"))
            return new Folder(VIDEO,"local","",cleaned.substring(6));
        if(cleaned.startsWith("remote:")){
            int split=cleaned.indexOf(':',7);
            if(split>7&&split==cleaned.length()-1)
                return new Folder(VIDEO,"remote",
                        cleaned.substring(7,split),"/");
            if(split>7&&split+1<cleaned.length()&&
                    cleaned.charAt(split+1)=='/')
                return new Folder(VIDEO,"remote",
                        cleaned.substring(7,split),
                        RemoteProvider.relativePath(cleaned.substring(split+1)));
        }
        if(cleaned.startsWith("saf:")){
            // The tree owner is a content:// URI containing its own colon;
            // the final ":/" introduces the folder-relative path.
            int split=cleaned.lastIndexOf(":/");
            if(split>4&&split+1<cleaned.length())
                return new Folder(VIDEO,"saf",
                        cleaned.substring(4,split),
                        RemoteProvider.relativePath(cleaned.substring(split+1)));
            if(cleaned.endsWith(":")&&cleaned.length()>5)
                return new Folder(VIDEO,"saf",
                        cleaned.substring(4,cleaned.length()-1),"/");
        }
        if(cleaned.startsWith("storage:")&&cleaned.length()>8)
            return new Folder(VIDEO,"storage","",cleaned.substring(8));
        return null;
    }
    /** SAF documentId to a relative path within an authorized tree root. */
    static String relativeDocumentId(String rootDocumentId,String markedId){
        if(rootDocumentId==null||markedId==null)return null;
        if(markedId.equals(rootDocumentId))return "/";
        String suffix;
        if(rootDocumentId.endsWith(":")&&
                markedId.startsWith(rootDocumentId))
            suffix=markedId.substring(rootDocumentId.length());
        else if(markedId.startsWith(rootDocumentId+"/"))
            suffix=markedId.substring(rootDocumentId.length()+1);
        else return null;
        return suffix.isEmpty()?"/":RemoteProvider.relativePath("/"+suffix);
    }
    static List<Folder> collect(String mode,Set<String> videoScopes,
                                List<ReaderFolders.Scope> readers){
        LinkedHashMap<String,Folder> dedup=new LinkedHashMap<>();
        if(VIDEO.equals(mode)&&videoScopes!=null){
            for(String scope:videoScopes){
                Folder folder=fromVideoScope(scope);
                if(folder!=null)dedup.put(folder.key(),folder);
            }
        }else if((COMIC.equals(mode)||TEXT.equals(mode))&&readers!=null){
            for(ReaderFolders.Scope scope:readers){
                if(!mode.equals(scope.mode))continue;
                Folder folder=new Folder(mode,scope.kind,scope.owner,scope.path);
                dedup.put(folder.key(),folder);
            }
        }
        ArrayList<Folder> result=new ArrayList<>(dedup.values());
        Collections.sort(result,Comparator.comparing(
                folder->folder.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** A displayed shelf item can be a child of a configured folder. */
    static Folder configuredRoot(List<Folder> savedRoots, Folder openedChild) {
        if (openedChild == null) return null;
        Folder best = null;
        if (savedRoots != null) for (Folder root : savedRoots) {
            if (!openedChild.mode.equals(root.mode) ||
                    !openedChild.kind.equals(root.kind) ||
                    !openedChild.owner.equals(root.owner)) continue;
            String parent = normalized(root.path);
            String child = normalized(openedChild.path);
            if (!(child.equals(parent) || parent.equals("/") ||
                    child.startsWith(parent + "/"))) continue;
            if (best == null || parent.length() > normalized(best.path).length())
                best = root;
        }
        return best == null ? openedChild : best;
    }

    private static String normalized(String path) {
        if (path == null || path.isEmpty()) return "/";
        String p = path.replace('\\', '/');
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
