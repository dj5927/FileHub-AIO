package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CloudDownloadHostTest {
    @Test public void includeTeraboxSignedDlinkSubdomainAndFirstPartyServers() {
        assertTrue(CloudProvider.trustedTeraboxDownloadHost("d.terabox.com"));
        assertTrue(CloudProvider.trustedTeraboxDownloadHost("www.terabox.com"));
        assertTrue(CloudProvider.trustedTeraboxDownloadHost("c-jp.terabox.com"));
        assertTrue(CloudProvider.trustedTeraboxDownloadHost("d.nephobox.com"));
        assertTrue(CloudProvider.trustedTeraboxDownloadHost("TERABOX.COM"));
    }
    @Test public void neverForwardNdusToUntrustedLookalikeOrThirdPartyCdn() {
        assertFalse(CloudProvider.trustedTeraboxDownloadHost("terabox.com.attacker.test"));
        assertFalse(CloudProvider.trustedTeraboxDownloadHost("fake-terabox.com"));
        assertFalse(CloudProvider.trustedTeraboxDownloadHost("terabox.com.evil"));
        assertFalse(CloudProvider.trustedTeraboxDownloadHost("another-download-cdn.com"));
        assertFalse(CloudProvider.trustedTeraboxDownloadHost("localhost"));
        assertFalse(CloudProvider.trustedTeraboxDownloadHost(null));
    }
}
