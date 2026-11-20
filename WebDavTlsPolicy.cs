using System.Collections.Concurrent;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;

namespace NASMount;

/// <summary>
/// A NAS may be reachable under several user-selected DDNS aliases. Never
/// infer the NAS vendor from the hostname and never maintain a DNS-provider
/// allowlist. Check the actual TLS peer, require a trusted certificate chain,
/// but permit only a hostname mismatch for the *configured* WebDAV endpoint.
/// This is intentionally narrower than rclone's --no-check-certificate.
/// </summary>
internal static class WebDavTlsPolicy
{
    private static readonly ConcurrentDictionary<string, byte> Reported =
        new(StringComparer.OrdinalIgnoreCase);

    internal static HttpClientHandler CreateHandler(NasProfile profile,
        bool allowRedirect = true)
    {
        return new HttpClientHandler
        {
            AllowAutoRedirect = allowRedirect,
            ServerCertificateCustomValidationCallback =
                (request, certificate, _, errors) =>
                {
                    bool accepted = AcceptCertificate(profile,
                        request.RequestUri, certificate is not null, errors);
                    if (accepted && errors == SslPolicyErrors.RemoteCertificateNameMismatch)
                    {
                        string endpoint = profile.Host + ":" +
                            (profile.Port > 0 ? profile.Port : 5006);
                        if (Reported.TryAdd(endpoint, 0))
                            AppStore.Log("WEBDAV_TLS_DDNS_ALIAS_ACCEPTED" +
                                " configuredHost=" + profile.Host +
                                " trustedChain=true nameMismatchOnly=true" +
                                " otherCertificateErrorsRejected=true");
                    }
                    return accepted;
                }
        };
    }

    internal static HttpClient CreateClient(NasProfile profile,
        TimeSpan timeout, bool allowRedirect = true) =>
        new(CreateHandler(profile, allowRedirect), disposeHandler: true)
        {
            Timeout = timeout
        };

    internal static bool AcceptCertificate(NasProfile profile,
        Uri? actualRequest, bool hasPeerCertificate, SslPolicyErrors errors)
    {
        if (!hasPeerCertificate) return false;
        if (errors == SslPolicyErrors.None) return true;
        // Never turn this into an unrestricted TLS bypass. NameMismatch must
        // be the ONLY error; an untrusted/expired chain still fails.
        if (errors != SslPolicyErrors.RemoteCertificateNameMismatch ||
            profile.Protocol != "WebDAV" || profile.WebDavUseHttp ||
            actualRequest?.Scheme != Uri.UriSchemeHttps)
            return false;
        int intendedPort = profile.Port > 0 ? profile.Port : 5006;
        return actualRequest.Port == intendedPort &&
            string.Equals(actualRequest.Host, profile.Host,
                StringComparison.OrdinalIgnoreCase);
    }

    internal static void SelfTest()
    {
        var profile = new NasProfile
        {
            Protocol = "WebDAV", Host = "ormshop.iptime.org", Port = 5006
        };
        var uri = new Uri("https://ormshop.iptime.org:5006/");
        bool Accept(SslPolicyErrors errors) =>
            AcceptCertificate(profile, uri, true, errors);
        if (!Accept(SslPolicyErrors.None) ||
            !Accept(SslPolicyErrors.RemoteCertificateNameMismatch) ||
            Accept(SslPolicyErrors.RemoteCertificateChainErrors) ||
            Accept(SslPolicyErrors.RemoteCertificateNotAvailable) ||
            Accept(SslPolicyErrors.RemoteCertificateNameMismatch |
                SslPolicyErrors.RemoteCertificateChainErrors) ||
            AcceptCertificate(profile, uri, false, SslPolicyErrors.None) ||
            AcceptCertificate(profile,
                new Uri("https://other.iptime.org:5006/"), true,
                SslPolicyErrors.RemoteCertificateNameMismatch) ||
            AcceptCertificate(profile,
                new Uri("https://ormshop.iptime.org:5443/"), true,
                SslPolicyErrors.RemoteCertificateNameMismatch))
            throw new IOException("DDNS alias TLS validation policy regression");
        profile.WebDavUseHttp = true;
        if (Accept(SslPolicyErrors.RemoteCertificateNameMismatch))
            throw new IOException("HTTP profile unexpectedly skipped TLS");
        Console.WriteLine("WEBDAV_DDNS_ALIAS_TLS_POLICY_PASS");
    }
}
