package com.nasmount.mobile;

import java.util.*;

/** UI-only commands; positions are bounded and actual content remains native. */
final class ViewerUiPolicy {
    private ViewerUiPolicy(){}
    static final Set<String> COMMANDS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "back","previous","next","play","settings","quick","seek","save","search","classic")));
    static boolean accepts(String nonce,Object incomingNonce,Object command,Object value,Collection<?> enabled) {
        if(!nonce.equals(incomingNonce)||!(command instanceof String)||
                !COMMANDS.contains(command)||!enabled.contains(command))return false;
        return "seek".equals(command)?value instanceof Integer&&(Integer)value>=0&&(Integer)value<=1000:value==null;
    }
    static Map<String,Object> state(String mode,String label,int bg,int fg,int progress,
                                    boolean playing,String... enabled){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("mode",mode);data.put("label",label==null?"":label);
        data.put("bg",bg&0xffffffffL);data.put("fg",fg&0xffffffffL);
        data.put("progress",Math.max(-1,Math.min(1000,progress)));
        data.put("playing",playing);
        List<String> actions=new ArrayList<>();
        for(String action:enabled)if(action!=null&&COMMANDS.contains(action))actions.add(action);
        if(!actions.contains("classic"))actions.add("classic");
        data.put("enabled",actions);
        return data;
    }
    static Map<String,Object> document(String name,int bg,int fg,boolean hasSave,
                                       boolean ready,boolean busy,boolean readOnly){
        Map<String,Object> data=state("document",name,bg,fg,-1,false,"back",
                hasSave&&ready&&!busy&&!readOnly?"save":null,
                hasSave&&ready&&!busy?"search":null);
        data.put("hasSave",hasSave);data.put("hasSearch",hasSave);
        data.put("readOnly",readOnly);data.put("busy",busy);
        return data;
    }
}
