package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

/** Browser-redirect policy tests, no real TeraBox login or cookies used. */
public final class TeraBoxWebPolicyTest {
    @Test public void opensOfficialDesktopLoginInWebView() {
        assertTrue(TeraBoxWebPolicy.isAllowedWebNavigation(
                TeraBoxWebPolicy.LOGIN_URL));
        assertTrue(TeraBoxWebPolicy.DESKTOP_USER_AGENT.contains("X11; Linux"));
        assertTrue(TeraBoxWebPolicy.isAllowedWebNavigation(
                "https://www.terabox.com/korean/home"));
        assertTrue(TeraBoxWebPolicy.isAllowedWebNavigation(
                "https://www.terabox.app/login"));
    }
    @Test public void blocksAppInstallNavigation() {
        for (String value : new String[]{
                "market://details?id=com.terabox",
                "intent://open#Intent;package=com.terabox;end",
                "terabox://login",
                "https://play.google.com/store/apps/details?id=com.terabox",
                "https://apps.apple.com/us/app/terabox",
                "https://www.terabox.com/korean/download",
                "https://www.terabox.com/install",
                "https://www.terabox.com/terabox.apk"
        }) assertFalse(value, TeraBoxWebPolicy.isAllowedWebNavigation(value));
    }
    @Test public void blocksUnrelatedAndDisguisedDomains() {
        for (String value : new String[]{
                "https://terabox.com.evil.example/login",
                "https://example.com/terabox.com/login",
                "https://play.google.com.evil.example/store",
                "http://www.terabox.com/korean/login",
                "https://www.terabox.com@evil.com/login",
                "javascript:alert(1)",
                "data:text/html,test"
        }) assertFalse(value, TeraBoxWebPolicy.isAllowedWebNavigation(value));
    }
    @Test public void permitsProviderIdentityWebsites() {
        // Google OAuth must never load in an embedded WebView.
        assertFalse(TeraBoxWebPolicy.isAllowedWebNavigation(
                "https://accounts.google.com/signin"));
        assertTrue(TeraBoxWebPolicy.isGoogleAuthNavigation(
                "https://accounts.google.com/o/oauth2/v2/auth"));
        assertFalse(TeraBoxWebPolicy.isGoogleAuthNavigation(
                "https://accounts.google.com.attacker.example/o/oauth2/v2/auth"));
        assertFalse(TeraBoxWebPolicy.isGoogleAuthNavigation(
                "http://accounts.google.com/signin"));
        assertTrue(TeraBoxWebPolicy.isAllowedWebNavigation(
                "https://www.facebook.com/login.php"));
    }
}
