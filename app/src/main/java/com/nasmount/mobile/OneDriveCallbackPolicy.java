package com.nasmount.mobile;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Exact registered URI, one state and one code; never trust a browser callback alone. */
final class OneDriveCallbackPolicy {
    static final long MAX_AGE=15*60*1000L;
    static final class Result {
        final String state,code;
        final boolean denied;
        Result(String s,String c,boolean d){state=s;code=c;denied=d;}
    }
    static Result parse(String raw,String redirect)throws Exception{
        if(raw==null||raw.length()>32768)throw new IOException("Invalid OneDrive callback");
        URI u=new URI(raw),expected=new URI(redirect);
        if(!expected.getScheme().equals(u.getScheme())||
           !expected.getRawAuthority().equals(u.getRawAuthority())||
           !java.util.Objects.equals(expected.getRawPath(),u.getRawPath())||
           u.getRawFragment()!=null||u.getRawQuery()==null)throw new IOException("Invalid OneDrive callback URI");
        Map<String,String> params=new HashMap<>();
        for(String part:u.getRawQuery().split("&",-1)){
            int n=part.indexOf('=');
            if(n<1)throw new IOException("Invalid OneDrive callback parameter");
            String key=URLDecoder.decode(part.substring(0,n),"UTF-8");
            String value=URLDecoder.decode(part.substring(n+1),"UTF-8");
            if(params.put(key,value)!=null)throw new IOException("Duplicate OneDrive callback parameter");
        }
        String state=params.get("state"),code=params.get("code");
        boolean denied=params.containsKey("error");
        if(state==null||state.isEmpty()||state.length()>512||
            (denied?code!=null:code==null||code.isEmpty()||code.length()>16384))
            throw new IOException("Invalid OneDrive callback payload");
        return new Result(state,code,denied);
    }
    static boolean matches(String expected,String actual,long created,long now){
        return expected!=null&&actual!=null&&created>0&&now>=created&&now-created<=MAX_AGE&&
            MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
    private OneDriveCallbackPolicy(){}
}
