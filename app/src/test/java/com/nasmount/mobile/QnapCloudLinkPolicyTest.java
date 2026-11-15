package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class QnapCloudLinkPolicyTest {
    @Test public void portalIsDefaultAndOfficialSmartUrlsAllowed() {
        assertEquals("https://www.myqnapcloud.com/", QnapCloudLinkPolicy.smartUrl(""));
        assertEquals("https://qlink.to/family-nas",
                QnapCloudLinkPolicy.smartUrl("https://qlink.to/family-nas"));
        assertEquals("https://www.myqnapcloud.com/",
                QnapCloudLinkPolicy.smartUrl("https://www.myqnapcloud.com/"));
    }

    @Test public void untrustedAndInsecureDomainsAreBlocked() {
        for (String url : new String[]{
                "http://qlink.to/test", "https://qlink.to.evil.example/test",
                "https://evil.example/", "https://user:secret@qlink.to/test",
                "https://qlink.to:443/test", "https://qlink.to/test#fragment"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> QnapCloudLinkPolicy.smartUrl(url));
        }
    }

    @Test public void smartShareValidationDoesNotAllowLookalikeHosts() {
        assertTrue(QnapCloudLinkPolicy.isSmartShare("https://qlink.to/my-nas"));
        assertFalse(QnapCloudLinkPolicy.isSmartShare("https://notqlink.to/my-nas"));
        assertFalse(QnapCloudLinkPolicy.isSmartShare("http://qlink.to/my-nas"));
        assertFalse(QnapCloudLinkPolicy.isSmartShare("https://qlink.to.evil.org/"));
    }
}
