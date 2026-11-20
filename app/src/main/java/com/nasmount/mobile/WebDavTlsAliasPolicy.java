package com.nasmount.mobile;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * A NAS can have multiple DDNS aliases independent of its vendor.
 *
 * OkHttp performs Android's normal CA certificate-chain validation before
 * calling this hostname verifier. Accept only the selected WebDAV host's
 * DNS alias when the peer certificate is present, valid, and CA-trusted.
 * Other HTTP clients and cloud providers remain unchanged.
 */
final class WebDavTlsAliasPolicy {
    private WebDavTlsAliasPolicy() {}

    static boolean allowConfiguredAlias(String protocol, boolean https,
                                        String configuredHost, String requestedHost,
                                        boolean hasPeerCertificate) {
        return "WebDAV".equals(protocol) && https && hasPeerCertificate &&
                configuredHost != null && !configuredHost.trim().isEmpty() &&
                requestedHost != null &&
                configuredHost.trim().toLowerCase(Locale.ROOT).equals(
                        requestedHost.toLowerCase(Locale.ROOT));
    }

    static HostnameVerifier forProfile(Models.RemoteProfile profile) {
        return forConfiguredHttpsHost(profile.host, profile.ssl, profile.protocol);
    }

    // NAS File Station management is a separate explicit user-provided URL,
    // not an auto-detected Synology host or DDNS vendor special case.
    static HostnameVerifier forConfiguredHttpsHost(String configuredHost,
                                                   boolean https, String protocol) {
        final HostnameVerifier standard =
                HttpsURLConnection.getDefaultHostnameVerifier();
        return (requestedHost, session) -> {
            if (standard.verify(requestedHost, session)) return true;
            try {
                Certificate[] certificates = session.getPeerCertificates();
                if (certificates.length == 0 ||
                        !(certificates[0] instanceof X509Certificate))
                    return false;
                ((X509Certificate) certificates[0]).checkValidity();
                return allowConfiguredAlias(protocol, https, configuredHost,
                        requestedHost, true);
            } catch (SSLPeerUnverifiedException |
                     java.security.cert.CertificateException invalid) {
                return false;
            }
        };
    }

    static HostnameVerifier forNasManagement(String configuredHost, boolean https) {
        return forConfiguredHttpsHost(configuredHost, https, "WebDAV");
    }
}
