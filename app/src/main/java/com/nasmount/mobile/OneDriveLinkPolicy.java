package com.nasmount.mobile;

import java.util.List;

/** Resolve a repeated connection to the same stable Graph user/drive identity. */
final class OneDriveLinkPolicy {
    static Models.RemoteProfile choose(List<Models.RemoteProfile> profiles,String editId,String marker)
            throws java.io.IOException {
        if(editId!=null){
            for(Models.RemoteProfile p:profiles)if(editId.equals(p.id)){
                if(OneDriveAuth.linked(p)&&marker.equals(p.fingerprint))return p;
                throw new java.io.IOException("OneDrive account mismatch");
            }
            throw new java.io.IOException("OneDrive profile no longer exists");
        }
        for(Models.RemoteProfile p:profiles)
            if(OneDriveAuth.linked(p)&&marker.equals(p.fingerprint))return p;
        return new Models.RemoteProfile();
    }
    static boolean visible(List<Models.RemoteProfile> profiles,String id,String marker){
        for(Models.RemoteProfile p:profiles)
            if(id.equals(p.id)&&marker.equals(p.fingerprint)&&OneDriveAuth.linked(p))return true;
        return false;
    }
    private OneDriveLinkPolicy(){}
}
