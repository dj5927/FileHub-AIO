package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SdAccessPolicyTest {
    @Test public void identifiesOnlyMountedNonPrimaryRemovableCards() {
        assertTrue(SdAccessPolicy.isMountedSd(true,false,"mounted"));
        assertTrue(SdAccessPolicy.isMountedSd(true,false,"mounted_ro"));
        assertFalse(SdAccessPolicy.isMountedSd(true,true,"mounted"));
        assertFalse(SdAccessPolicy.isMountedSd(false,false,"mounted"));
        assertFalse(SdAccessPolicy.isMountedSd(true,false,"unmounted"));
    }
    @Test public void directAccessRequiresBothPermissionAndReadableRoot() {
        assertTrue(SdAccessPolicy.useDirectRoot(35,true,true));
        assertFalse(SdAccessPolicy.useDirectRoot(35,false,true));
        assertFalse(SdAccessPolicy.useDirectRoot(35,true,false));
        assertFalse(SdAccessPolicy.useDirectRoot(29,true,true));
    }
    @Test public void rejectsInternalStorageOrWrongCardInSdPicker() {
        String authority="com.android.externalstorage.documents";
        assertTrue(SdAccessPolicy.validSdDocumentId(authority,
                "1234-ABCD:Pictures/2026","1234-ABCD"));
        assertTrue(SdAccessPolicy.validSdDocumentId(authority,
                "1234-ABCD:","1234-abcd"));
        assertFalse(SdAccessPolicy.validSdDocumentId(authority,
                "primary:Pictures","1234-ABCD"));
        assertFalse(SdAccessPolicy.validSdDocumentId(authority,
                "9876-4321:DCIM","1234-ABCD"));
        assertTrue(SdAccessPolicy.validSdDocumentId(authority,
                "1234-ABCD:",""));
        assertFalse(SdAccessPolicy.validSdDocumentId("com.example.fake",
                "1234-ABCD:Photos","1234-ABCD"));
    }
    @Test public void fallbackRootFromOwnedAppFilesPath() {
        assertEquals("/storage/1234-ABCD",SdAccessPolicy.rootFromAppFilesPath(
                "/storage/1234-ABCD/Android/data/com.nasmount.mobile/files"));
        assertNull(SdAccessPolicy.rootFromAppFilesPath(
                "/data/user/0/com.nasmount.mobile/files"));
        assertNull(SdAccessPolicy.rootFromAppFilesPath(null));
    }
}
