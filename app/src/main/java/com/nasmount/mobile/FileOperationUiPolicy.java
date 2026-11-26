package com.nasmount.mobile;

import java.util.*;

/** A UI command never carries paths or a replacement selection. */
final class FileOperationUiPolicy {
    static final Set<String> ACTIONS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "selectAll","clearSelection","copySelected","moveSelected","renameSelected",
            "deleteSelected","selectedMore","pasteClipboard","clearClipboard")));
    private FileOperationUiPolicy(){}
    static boolean valid(String action,String revision){
        return ACTIONS.contains(action)&&revision!=null&&revision.matches("[0-9a-f-]{36}");
    }
    static boolean permits(String action,int selected,int selectable,int staged,
                           boolean allWritable,boolean destination,boolean destWritable,
                           boolean transferActive){
        switch(action){
            case "selectAll":return selected>0&&selectable>0;
            case "clearSelection":return selected>0;
            case "copySelected":return selected>0&&!transferActive;
            case "moveSelected":return selected>0&&allWritable&&!transferActive;
            case "renameSelected":return selected==1&&allWritable;
            case "deleteSelected":return selected>0&&allWritable;
            case "selectedMore":return selected>0;
            case "pasteClipboard":return selected==0&&staged>0&&destination&&destWritable&&!transferActive;
            case "clearClipboard":return selected==0&&staged>0&&destination;
            default:return false;
        }
    }
    static Map<String,Object> transfer(boolean active,boolean cancelling,boolean finished,
                                      boolean failed,boolean cancelled,long done,long total,
                                      String name,int completed,int count,boolean moving){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("active",active);data.put("cancelling",cancelling);
        data.put("finished",finished&&!active&&!failed&&!cancelled);
        data.put("failed",failed);data.put("cancelled",cancelled);
        data.put("percent",TransferProgressFormat.percent(done,total,finished&&!active&&!failed&&!cancelled));
        data.put("done",done);data.put("total",total);data.put("name",name==null?"":name);
        data.put("completed",completed);data.put("count",count);data.put("moving",moving);
        return data;
    }
}
