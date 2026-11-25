package com.nasmount.mobile;

import java.util.*;

/** No Android dependencies: policy is shared by UI, secure storage and tests. */
final class MonetizationPolicy {
    static final long AD_INTERVAL_MS=10L*60*1000;
    static final String PRODUCT_ID="filehub_full_version";
    static final int KRW_PRICE=3900;
    private MonetizationPolicy() {}
    static String type(String protocol) {
        return protocol==null?"":protocol.trim().toUpperCase(Locale.ROOT);
    }
    static List<Models.RemoteProfile> accessible(List<Models.RemoteProfile> saved,boolean full) {
        if(full)return new ArrayList<>(saved);
        List<Models.RemoteProfile> allowed=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Models.RemoteProfile p:saved)if(seen.add(type(p.protocol)))allowed.add(p);
        return allowed;
    }
    static boolean canSave(List<Models.RemoteProfile> saved,String id,String protocol,boolean full) {
        if(full)return true;
        String group=type(protocol);
        // Existing active profile can be edited, including its credentials.
        for(Models.RemoteProfile p:saved) {
            if(!type(p.protocol).equals(group))continue;
            return Objects.equals(p.id,id);
        }
        return true;
    }
    static boolean allowed(List<Models.RemoteProfile> saved,String id,boolean full) {
        for(Models.RemoteProfile p:accessible(saved,full))
            if(Objects.equals(p.id,id))return true;
        return false;
    }

    /** Single accumulated allowance across comics, novels and video.
     * Track only foreground elapsed time; reset only on actual ad impression. */
    static final class UsageClock {
        private long elapsed;
        private long start=-1;
        UsageClock(long restored){elapsed=Math.max(0,Math.min(AD_INTERVAL_MS,restored));}
        void update(long now,boolean counting) {
            if(start>=0)elapsed=Math.min(AD_INTERVAL_MS,elapsed+Math.max(0,now-start));
            start=counting&&elapsed<AD_INTERVAL_MS?now:-1;
        }
        boolean due(){return elapsed>=AD_INTERVAL_MS;}
        long accumulated(){return elapsed;}
        void shown(){elapsed=0;start=-1;}
    }
}
