package com.nasmount.mobile;

import java.util.Locale;

/**
 * A media override belongs to the DIRECTORY CONTAINING the file.
 * A/1.zip and A/10.zip must use the same settings; B/1.zip must not.
 * No user-visible files, book progress or connection credentials are touched.
 */
final class FolderSettingsPolicy {
    private FolderSettingsPolicy(){}

    static String scope(String kind,String owner,String filePath){
        if(kind==null||filePath==null||filePath.trim().isEmpty())
            return "";
        String type=kind.trim().toLowerCase(Locale.ROOT);
        String account=owner==null?"":owner.trim();
        String path=filePath.trim().replace('\\','/');
        while(path.length()>1&&path.endsWith("/"))
            path=path.substring(0,path.length()-1);
        int last=path.lastIndexOf('/');
        String parent;
        if(last<0){
            int colon=path.indexOf(':');
            // In SAF, documentId may be "primary:MyBook.epub" at root.
            parent=colon>=0?path.substring(0,colon+1):"/";
        }else if(last==0)parent="/";
        else parent=path.substring(0,last);
        return type+"|"+account+"|"+parent;
    }
    static String group(String category,String scope){
        if(category==null||scope==null||scope.isEmpty())return "";
        return category+"|"+scope;
    }
}
