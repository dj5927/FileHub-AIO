using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
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
    private static Uri Endpoint(NasProfile profile) =>
        new UriBuilder("https", profile.Host, profile.Port > 0 ? profile.Port : 5006).Uri;

    internal static string AliasPin(NasProfile profile, string sha256) =>
        Endpoint(profile).IdnHost.ToLowerInvariant() + ":" + Endpoint(profile).Port + ":" + sha256;

    // Probe without sending credentials, and do not accept the mismatched TLS
    // connection during observation. The user must independently verify it.
    internal static async Task<string?> ObserveAliasAsync(NasProfile profile)
    {
        if (profile.Protocol != "WebDAV" || profile.WebDavUseHttp) return null;
        var uri = Endpoint(profile);
        string? observed = null;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var socket = new TcpClient();
        await socket.ConnectAsync(uri.IdnHost, uri.Port, timeout.Token);
        using var tls = new SslStream(socket.GetStream(), false, (_, cert, _, errors) =>
        {
            if (cert is not null && errors == SslPolicyErrors.RemoteCertificateNameMismatch)
                observed = cert.GetCertHashString(HashAlgorithmName.SHA256);
            return cert is not null && errors == SslPolicyErrors.None;
        });
        try
        {
            // TLS only: some NAS servers reject unauthenticated OPTIONS or
            // close the HTTP connection after TLS. Neither implies bad TLS.
            // No credentials, HTTP methods or request paths are sent here.
            await tls.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = uri.IdnHost
            }, timeout.Token);
        }
        catch (AuthenticationException) when (observed is not null) { }
        catch (IOException) when (observed is not null) { }
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
        Uri intended = Endpoint(profile);
        return sha256 is not null && sha256.Length == 64 &&
            string.Equals(profile.WebDavAliasPin, AliasPin(profile, sha256), StringComparison.OrdinalIgnoreCase) &&
            actualRequest.Port == intended.Port &&
            string.Equals(actualRequest.IdnHost, intended.IdnHost,
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
        // Provider names and port numbers have no influence on trust.
        foreach (string host in new[] { "nas.iptime.org", "nas.synology.me",
            "nas.myqnapcloud.com", "nas.duckdns.org", "nas.ddns.net",
            "nas.dyndns.org", "nas.asuscomm.com", "nas.example.test", "192.0.2.10" })
        {
            var custom = new NasProfile { Protocol = "WebDAV", Host = host, Port = 8443 };
            var endpoint = Endpoint(custom);
            if (!AcceptCertificate(custom, endpoint, true, SslPolicyErrors.None) ||
                AcceptCertificate(custom, endpoint, true, SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint))
                throw new IOException("DDNS provider-independent initial trust failed");
            custom.WebDavAliasPin = AliasPin(custom, fingerprint);
            if (!AcceptCertificate(custom, endpoint, true, SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint) ||
                AcceptCertificate(custom, new UriBuilder(endpoint) { Port = 8444 }.Uri, true,
                    SslPolicyErrors.RemoteCertificateNameMismatch, fingerprint) ||
                AcceptCertificate(custom, endpoint, true, SslPolicyErrors.RemoteCertificateChainErrors, fingerprint))
                throw new IOException("DDNS provider-independent pin failed");
        }
        Console.WriteLine("WEBDAV_ALL_DDNS_CUSTOM_PORT_PIN_POLICY_PASS");
        Console.WriteLine("WEBDAV_DDNS_ALIAS_TLS_POLICY_PASS");
    }
}
