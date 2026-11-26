package com.nasmount.mobile;

import java.util.*;
import java.util.function.IntConsumer;

/** Presentation-only rows. Commands are bound to one native render revision. */
final class NativePanelModel {
    private final String revision=UUID.randomUUID().toString();
    private final String title;
    private final List<String> tabs;
    private final int selected;
    private final IntConsumer tabChanged;
    private final List<Row> rows=new ArrayList<>();
    private final Map<Integer,Runnable> folderActions=new HashMap<>();

    private static final class Row {
        final String kind,title,detail;
        final boolean enabled;
        final Runnable action;
        final List<String> choices;
        final int selected;
        final IntConsumer choose;
        Row(String kind,String title,String detail,boolean enabled,Runnable action,
                List<String> choices,int selected,IntConsumer choose){
            this.kind=kind;this.title=title;this.detail=detail;this.enabled=enabled;
            this.action=action;this.choices=choices;this.selected=selected;this.choose=choose;
        }
    }
    NativePanelModel(String title,String[] tabs,int selected,IntConsumer tabChanged){
        this.title=title;
        this.tabs=Collections.unmodifiableList(Arrays.asList(tabs.clone()));
        this.selected=selected;this.tabChanged=tabChanged;
    }
    private void plain(String kind,String title,String detail){
        rows.add(new Row(kind,title,detail,false,null,Collections.emptyList(),-1,null));
    }
    void section(String title){plain("section",title,"");}
    void note(String detail){plain("note","",detail);}
    void action(String title,String detail,boolean enabled,Runnable action){
        rows.add(new Row("action",title,detail,enabled,action,Collections.emptyList(),-1,null));
    }
    void folder(String title,String detail,Runnable open,Runnable remove){
        int index=rows.size();
        rows.add(new Row("folder",title,detail,true,open,Collections.emptyList(),-1,null));
        folderActions.put(index,remove);
    }
    void lastChoice(String[] choices,int selected,IntConsumer choose){
        if(rows.isEmpty()||choices.length==0||choose==null)throw new IllegalArgumentException("choice");
        int last=rows.size()-1;
        Row row=rows.get(last);
        if(!"action".equals(row.kind))throw new IllegalStateException("choice row");
        rows.set(last,new Row("choice",row.title,row.detail,row.enabled,null,
                Collections.unmodifiableList(Arrays.asList(choices.clone())),
                Math.max(0,Math.min(choices.length-1,selected)),choose));
    }
    Map<String,Object> snapshot(){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("revision",revision);data.put("title",title);data.put("tabs",tabs);data.put("selected",selected);
        List<Map<String,Object>> output=new ArrayList<>();
        for(int i=0;i<rows.size();i++){
            Row row=rows.get(i);
            Map<String,Object> r=new LinkedHashMap<>();
            r.put("id","r"+i);r.put("kind",row.kind);r.put("title",row.title);
            r.put("detail",row.detail);r.put("enabled",row.enabled);
            if("choice".equals(row.kind)){
                r.put("choices",row.choices);r.put("selected",row.selected);
            }
            output.add(r);
        }
        data.put("rows",output);return data;
    }
    Runnable command(Object generation,Object id,Object value){
        if(!revision.equals(generation)||!(id instanceof String))return null;
        if("tab".equals(id)){
            if(tabChanged==null||!(value instanceof Integer))return null;
            int i=(Integer)value;
            return i>=0&&i<tabs.size()?()->tabChanged.accept(i):null;
        }
        for(int i=0;i<rows.size();i++){
            Row row=rows.get(i);
            if(("r"+i+":remove").equals(id))
                return value==null&&row.enabled?folderActions.get(i):null;
            if(!("r"+i).equals(id))continue;
            if(!row.enabled)return null;
            if(("action".equals(row.kind)||"folder".equals(row.kind))&&value==null)return row.action;
            if("choice".equals(row.kind)&&value instanceof Integer){
                int index=(Integer)value;
                if(index>=0&&index<row.choices.size())return ()->row.choose.accept(index);
            }
            return null;
        }
        return null;
    }
    boolean matches(Object value){return revision.equals(value);}
}
