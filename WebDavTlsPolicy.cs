using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace NASMount;

/// <summary>
/// A NAS may be reachable under several user-selected DDNS aliases. Never
/// infer the NAS vendor from the hostname and never maintain a DNS-provider
/// allowlist. Check the actual TLS peer, require a trusted certificate chain,
/// and require explicit certificate pin approval for a hostname mismatch.
/// This is intentionally narrower than rclone's --no-check-certificate.
/// </summary>
internal static class WebDavTlsPolicy
{
    internal static string AliasPin(NasProfile profile, string sha256) =>
        profile.Host.ToLowerInvariant() + ":" + (profile.Port > 0 ? profile.Port : 5006) + ":" + sha256;

    // Probe without sending credentials, and do not accept the mismatched TLS
    // connection during observation. The user must independently verify it.
    internal static async Task<string?> ObserveAliasAsync(NasProfile profile)
    {
        if (profile.Protocol != "WebDAV" || profile.WebDavUseHttp) return null;
        var uri = new UriBuilder("https", profile.Host, profile.Port > 0 ? profile.Port : 5006).Uri;
        string? observed = null;
        using var handler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
        handler.ServerCertificateCustomValidationCallback = (_, cert, _, errors) =>
        {
            if (cert is not null && errors == SslPolicyErrors.RemoteCertificateNameMismatch)
                observed = cert.GetCertHashString(HashAlgorithmName.SHA256);
            return cert is not null && errors == SslPolicyErrors.None;
        };
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(15) };
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Options, uri);
            using var response = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead);
        }
        catch (HttpRequestException) when (observed is not null) { }
        return observed;
    }

    internal static HttpClientHandler CreateHandler(NasProfile profile,
        bool allowRedirect = false)
    {
        return new HttpClientHandler
        {
            AllowAutoRedirect = allowRedirect,
            ServerCertificateCustomValidationCallback =
                (request, certificate, _, errors) =>
                {
                    return AcceptCertificate(profile, request.RequestUri,
                        certificate is not null, errors,
                        certificate?.GetCertHashString(HashAlgorithmName.SHA256));
                }
        };
    }

    internal static HttpClient CreateClient(NasProfile profile,
        TimeSpan timeout, bool allowRedirect = false) =>
        new(CreateHandler(profile, allowRedirect), disposeHandler: true)
        {
            Timeout = timeout
        };

    internal static bool AcceptCertificate(NasProfile profile,
        Uri? actualRequest, bool hasPeerCertificate, SslPolicyErrors errors,
        string? sha256 = null)
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
        return sha256 is not null && sha256.Length == 64 &&
            string.Equals(profile.WebDavAliasPin, AliasPin(profile, sha256), StringComparison.OrdinalIgnoreCase) &&
            actualRequest.Port == intendedPort &&
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
        string fingerprint = new string('A', 64);
        if (AcceptCertificate(profile, uri, true, SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint))
            throw new IOException("Unapproved alias accepted");
        profile.WebDavAliasPin = AliasPin(profile, fingerprint);
        bool Accept(SslPolicyErrors errors) =>
            AcceptCertificate(profile, uri, true, errors, fingerprint);
        if (!Accept(SslPolicyErrors.None) ||
            !Accept(SslPolicyErrors.RemoteCertificateNameMismatch) ||
            Accept(SslPolicyErrors.RemoteCertificateChainErrors) ||
            Accept(SslPolicyErrors.RemoteCertificateNotAvailable) ||
            Accept(SslPolicyErrors.RemoteCertificateNameMismatch |
                SslPolicyErrors.RemoteCertificateChainErrors) ||
            AcceptCertificate(profile, uri, false, SslPolicyErrors.None) ||
            AcceptCertificate(profile,
                new Uri("https://other.iptime.org:5006/"), true,
                SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint) ||
            AcceptCertificate(profile,
                new Uri("https://ormshop.iptime.org:5443/"), true,
                SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint) ||
            AcceptCertificate(profile, uri, true, SslPolicyErrors.RemoteCertificateNameMismatch, new string('B', 64)))
            throw new IOException("DDNS alias TLS validation policy regression");
        profile.WebDavUseHttp = true;
        if (Accept(SslPolicyErrors.RemoteCertificateNameMismatch))
            throw new IOException("HTTP profile unexpectedly skipped TLS");
        Console.WriteLine("WEBDAV_DDNS_ALIAS_TLS_POLICY_PASS");
    }
}
