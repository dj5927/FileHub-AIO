package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

/** Only the user's actual WebDAV endpoint can use a trusted DDNS alias. */
public class WebDavTlsAliasPolicyTest {
    @Test public void acceptsKnownConfiguredWebDavAliasWithoutVendorRestriction() {
        assertTrue(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, "ormshop.iptime.org",
                "ormshop.iptime.org", true));
        assertTrue(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, "OTHER.CUSTOM-DNS.EXAMPLE",
                "other.custom-dns.example", true));
        assertTrue(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, "myserver.synology.me",
                "myserver.synology.me", true));
    }

    @Test public void doesNotAllowMissingCertificateOrDifferentEndpoint() {
        assertFalse(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, "ormshop.iptime.org",
                "attacker.example", true));
        assertFalse(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, "ormshop.iptime.org",
                "ormshop.iptime.org", false));
        assertFalse(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", false, "ormshop.iptime.org",
                "ormshop.iptime.org", true));
        assertFalse(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "TeraBox", true, "ormshop.iptime.org",
                "ormshop.iptime.org", true));
        assertFalse(WebDavTlsAliasPolicy.allowConfiguredAlias(
                "WebDAV", true, " ", " ", true));
    }
}
