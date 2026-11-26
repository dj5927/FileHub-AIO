package com.nasmount.mobile;

/** An explicit connection attempt must not silently finish when consent renewal was requested. */
final class GoogleDriveConsentPolicy {
    enum Action { LAUNCH_GOOGLE_CONSENT, OFFER_EXISTING_OR_RENEW, RENEWAL_NOT_DISPLAYED }
    static Action action(boolean hasResolution,boolean renewalRequested){
        if(hasResolution)return Action.LAUNCH_GOOGLE_CONSENT;
        return renewalRequested?Action.RENEWAL_NOT_DISPLAYED:Action.OFFER_EXISTING_OR_RENEW;
    }
    private GoogleDriveConsentPolicy(){}
}
