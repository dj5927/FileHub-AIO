using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace NASMount;

/// <summary>
/// Verify HTTPS certificate validity BEFORE creating a WinFsp drive.
/// An invalid cert currently lets rclone create an empty-looking disk which
/// then fails every PROPFIND with an I/O device error. Never disable TLS
/// validation or silently rewrite the configured host.
/// </summary>
internal static class WebDavTlsPreflight
{
    internal static async Task<OperationResult> CheckAsync(NasProfile p)
    {
        if (p.Protocol != "WebDAV")
            return OperationResult.Ok("Non-WebDAV connection");
        if (p.WebDavUseHttp)
        {
            // Synology WebDAV Server's default TLS port is 5006. HTTP
            // PROPFIND to a TLS socket returns EOF, not a browsable NAS.
            // Fail before starting WinFsp; do not silently change the host.
            if (p.Port == 5006)
                return OperationResult.Fail(HttpOnSynologyHttpsPortMessage(p.Host));
            return OperationResult.Ok("HTTP WebDAV; TLS not configured");
        }
        string host = p.Host.Trim();
        int port = p.Port <= 0 ? 5006 : p.Port;
        string? certificateName = null;
        SslPolicyErrors observedErrors = SslPolicyErrors.None;
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(9));
        try
        {
            using var tcp = new TcpClient();
            await tcp.ConnectAsync(host, port, deadline.Token);
            using var ssl = new SslStream(tcp.GetStream(), false,
                (_, certificate, _, errors) =>
                {
                    observedErrors = errors;
                    if (certificate is not null)
                    {
                        using var cert = new X509Certificate2(certificate);
                        certificateName = cert.GetNameInfo(X509NameType.DnsName, false);
                    }
                    // Strict public root + correct hostname + expiration.
                    return errors == SslPolicyErrors.None;
                });
            await ssl.AuthenticateAsClientAsync(
                new SslClientAuthenticationOptions { TargetHost = host },
                deadline.Token);
            AppStore.Log("WEBDAV_TLS_PREFLIGHT_OK id=" + p.Id);
            return OperationResult.Ok("WebDAV TLS certificate verified");
        }
        catch (Exception e)
        {
            string safeName = string.IsNullOrWhiteSpace(certificateName) ?
                "(인증서 이름 확인 불가)" : certificateName;
            string message = FailureMessage(host, safeName, observedErrors);
            AppStore.Log("WEBDAV_TLS_PREFLIGHT_FAILED id=" + p.Id +
                " type=" + e.GetType().Name +
                " mismatch=" + observedErrors.HasFlag(SslPolicyErrors.RemoteCertificateNameMismatch));
            return OperationResult.Fail(message);
        }
    }

    internal static string FailureMessage(string host, string certificateName,
        SslPolicyErrors errors)
    {
        if (errors.HasFlag(SslPolicyErrors.RemoteCertificateNameMismatch))
            return "WebDAV HTTPS 인증서 주소가 일치하지 않습니다.\n\n" +
                "연결에 입력한 주소: " + host + "\n" +
                "NAS 인증서의 도메인: " + certificateName + "\n\n" +
                "NAS에 설치된 인증서와 일치하는 주소를 사용하거나 " +
                "현재 주소를 포함하도록 NAS 인증서를 재발급하세요.\n" +
                "서버와 연결되지 않았으며 잘못된 인증서로 드라이브를 만들지 않았습니다.\n" +
                "보안 검증을 끄는 방식으로 연결하지 않습니다.";
        return "WebDAV HTTPS 인증서 또는 네트워크 연결 확인 실패.\n" +
            "설정 주소: " + host + "\n" +
            "서버 인증서 도메인: " + certificateName + "\n" +
            "DNS, 방화벽, 인증서 유효기간/발급기관을 확인하세요.\n" +
            "보안상 WinFsp 드라이브를 생성하지 않았습니다.";
    }

    internal static string HttpOnSynologyHttpsPortMessage(string host) =>
        "WebDAV 연결 설정에서 HTTP 사용이 켜져 있지만, 포트 5006은 " +
        "Synology WebDAV의 기본 HTTPS 포트입니다.\n\n" +
        "현재 설정: http://" + host + ":5006/\n\n" +
        "연결 설정에서 [HTTP 사용]을 해제해 HTTPS로 연결하세요.\n" +
        "인증서 도메인이 주소와 다르면 NAS 인증서에 등록된 " +
        "도메인을 사용하거나 인증서를 수정해야 합니다.\n" +
        "잘못된 프로토콜로 드라이브를 생성하지 않았습니다.";

    internal static void PolicyTest()
    {
        var wrongScheme = new NasProfile {
            Protocol = "WebDAV", Host = "ormshop.iptime.org",
            Port = 5006, WebDavUseHttp = true
        };
        var blocked = CheckAsync(wrongScheme).GetAwaiter().GetResult();
        if (blocked.Success || !blocked.Message.Contains("HTTP 사용") ||
            !blocked.Message.Contains("HTTPS"))
            throw new InvalidOperationException(
                "HTTP on Synology HTTPS port must fail before mount");
        string mismatch = FailureMessage("ormshop.iptime.org",
            "ormshop.synology.me", SslPolicyErrors.RemoteCertificateNameMismatch);
        if (!mismatch.Contains("ormshop.iptime.org") ||
            !mismatch.Contains("ormshop.synology.me") ||
            !mismatch.Contains("일치하지 않습니다") ||
            !mismatch.Contains("보안 검증을 끄는"))
            throw new InvalidOperationException("TLS mismatch must give safe host guidance");
        if (FailureMessage("localhost", "localhost",
            SslPolicyErrors.RemoteCertificateChainErrors)
            .Contains("주소가 일치하지 않습니다"))
            throw new InvalidOperationException("Certificate chain error misdiagnosed as name mismatch");
        Console.WriteLine("WEBDAV_TLS_FAIL_CLOSED_AND_HOSTNAME_MISMATCH_GUIDANCE_PASS");
    }
}
