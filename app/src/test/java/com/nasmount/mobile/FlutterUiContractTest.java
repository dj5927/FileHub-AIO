package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FlutterUiContractTest {
    @Test public void onlyReadOnlyExplorerRequestsCanPrecedeFirstFrame(){
        assertTrue(FlutterUiContract.passiveExplorerRequest("explorer","explorerPreview"));
        assertTrue(FlutterUiContract.passiveExplorerRequest("explorer","explorerPage"));
        for(String command:Arrays.asList("action","entryOpen","entrySelect","transferCancel","panelCommand","viewerCommand"))
            assertFalse(FlutterUiContract.passiveExplorerRequest("explorer",command));
        assertFalse(FlutterUiContract.passiveExplorerRequest("home","explorerPreview"));
        assertFalse(FlutterUiContract.passiveExplorerRequest("shelf","explorerPage"));
    }

    @Test public void navigationUsesOnlyTypedActionsAndOpaqueFavoriteIds() {
        assertTrue(FlutterUiContract.validNavigationAction("home","storage",null));
        assertTrue(FlutterUiContract.validNavigationAction("home","comicShelf",null));
        assertFalse(FlutterUiContract.validNavigationAction("home","nativeHome",null));
        assertFalse(FlutterUiContract.validNavigationAction("home","storage","/sdcard"));
        assertFalse(FlutterUiContract.validNavigationAction("drawer","delete","id"));
        assertFalse(FlutterUiContract.validNavigationAction("drawer","favorite","/private/path"));
        Models.Favorite a=new Models.Favorite("remote","A","/private/a","p");
        Models.Favorite b=new Models.Favorite("remote","B","/private/b","p");
        String idA=FlutterUiContract.favoriteId(a), idB=FlutterUiContract.favoriteId(b);
        assertNotEquals(idA,idB);
        assertTrue(FlutterUiContract.validNavigationAction("drawer","favorite",idA));
        List<Map<String,Object>> rows=FlutterUiContract.favoriteSummaries(Arrays.asList(a,b));
        assertEquals(new HashSet<>(Arrays.asList("id","label","original")),rows.get(0).keySet());
        assertFalse(rows.toString().contains("/private/"));
        assertEquals(Arrays.asList(b.key(),a.key()),FlutterUiContract.resolveFavoriteOrder(
                Arrays.asList(a,b),Arrays.asList(idB,idA)));
        try {
            FlutterUiContract.resolveFavoriteOrder(Arrays.asList(a,b),Arrays.asList(idA,idA));
            fail("duplicate order accepted");
        } catch (IllegalArgumentException expected) {}
        try {
            FlutterUiContract.resolveFavoriteOrder(Arrays.asList(a,b),Arrays.asList(idA));
            fail("stale order accepted");
        } catch (IllegalArgumentException expected) {}
    }
    @Test public void rejectsUnknownRoutesActionsAndValues() {
        assertTrue(FlutterUiContract.validRoute("connections"));
        assertTrue(FlutterUiContract.validRoute("interface"));
        assertFalse(FlutterUiContract.validRoute("file"));
        assertFalse(FlutterUiContract.validRoute(null));
        for (int i=0; i<5; i++) assertTrue(FlutterUiContract.validLanguage(i));
        assertFalse(FlutterUiContract.validLanguage(-1));
        assertFalse(FlutterUiContract.validLanguage(5));
        assertFalse(FlutterUiContract.validLanguage("1"));
        for (int i=0; i<3; i++) assertTrue(FlutterUiContract.validTheme(i));
        assertFalse(FlutterUiContract.validTheme(3));
        assertFalse(FlutterUiContract.validTheme(null));
        assertTrue(FlutterUiContract.validAction("connections","open","id"));
        assertTrue(FlutterUiContract.validAction("connections","manage","id"));
        assertTrue(FlutterUiContract.validAction("connections","backup",null));
        assertFalse(FlutterUiContract.validAction("interface","open","id"));
        assertFalse(FlutterUiContract.validAction("connections","delete","id"));
        assertFalse(FlutterUiContract.validAction("connections","open",""));
        assertFalse(FlutterUiContract.validAction("connections","manage",null));
    }

    @Test public void summariesKeepOnlyWhitelistedFieldsAndNativeEntitlement() {
        Models.RemoteProfile a = MonetizationPolicyTest.p("a","SMB");
        Models.RemoteProfile b = MonetizationPolicyTest.p("b","smb");
        a.name="My NAS"; b.name=""; b.readOnly=true;
        List<Models.RemoteProfile> profiles=Arrays.asList(a,b);
        List<Map<String,Object>> free=FlutterUiContract.summaries(profiles,false);
        assertEquals(2,free.size());
        Set<String> expected=new HashSet<>(Arrays.asList("id","label","protocol","allowed","readOnly"));
        for(Map<String,Object> row:free) assertEquals(expected,row.keySet());
        assertEquals("My NAS",free.get(0).get("label"));
        assertEquals("smb",free.get(1).get("label"));
        assertEquals(true,free.get(0).get("allowed"));
        assertEquals(false,free.get(1).get("allowed"));
        assertEquals(true,free.get(1).get("readOnly"));
        assertEquals(true,FlutterUiContract.summaries(profiles,true).get(1).get("allowed"));
    }
}
