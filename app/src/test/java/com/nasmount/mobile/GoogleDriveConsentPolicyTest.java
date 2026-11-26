package com.nasmount.mobile;
import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleDriveConsentPolicyTest {
    @Test public void firstAuthorizationLaunchesGoogleProvidedResolution(){
        assertEquals(GoogleDriveConsentPolicy.Action.LAUNCH_GOOGLE_CONSENT,GoogleDriveConsentPolicy.action(true,false));
    }
    @Test public void alreadyGrantedRequiresExplicitChoice(){
        assertEquals(GoogleDriveConsentPolicy.Action.OFFER_EXISTING_OR_RENEW,GoogleDriveConsentPolicy.action(false,false));
    }
    @Test public void renewalLaunchesGoogleProvidedResolution(){
        assertEquals(GoogleDriveConsentPolicy.Action.LAUNCH_GOOGLE_CONSENT,GoogleDriveConsentPolicy.action(true,true));
    }
    @Test public void renewalNeverSilentlyFinishesWithCachedGrant(){
        assertEquals(GoogleDriveConsentPolicy.Action.RENEWAL_NOT_DISPLAYED,GoogleDriveConsentPolicy.action(false,true));
    }
}
