using System.Net.Sockets;
using System.Text;

namespace NASMount;

internal enum FtpServiceKind { Unknown, Ftp, HttpWebDav, HttpOther }

/// <summary>
/// Identifies a misconfigured FTP port without authenticating or modifying
/// remote files. A greeting-only FTP probe precedes a read-only HTTP OPTIONS
/// request on ports that do not speak FTP first.
/// </summary>
internal static class FtpServiceDetector
{
    internal static async Task<FtpServiceKind> CheckAsync(string host, int port,
        CancellationToken cancellation = default)
    {
        try
        {
            using var client = new TcpClient();
            using var connectionTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            connectionTimeout.CancelAfter(TimeSpan.FromSeconds(3));
            await client.ConnectAsync(host, port, connectionTimeout.Token);
            await using var stream = client.GetStream();
            var buffer = new byte[4096];
            using (var greetingTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation))
            {
                greetingTimeout.CancelAfter(TimeSpan.FromMilliseconds(1050));
                try
                {
                    int received = await stream.ReadAsync(buffer, greetingTimeout.Token);
                    if (received > 0)
                    {
                        string greeting = Encoding.ASCII.GetString(buffer, 0, received);
                        if (greeting.StartsWith("220", StringComparison.Ordinal) ||
                            greeting.StartsWith("120", StringComparison.Ordinal))
                            return FtpServiceKind.Ftp;
                        if (greeting.StartsWith("HTTP/", StringComparison.OrdinalIgnoreCase))
                            return DetectHttp(greeting);
                    }
                }
                catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
                { /* Server has not greeted; probe whether it is HTTP. */ }
            }

            // No login, password, file listing, PUT, DELETE, or modification.
            string request = $"OPTIONS / HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(request), cancellation);
            using var responseTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            responseTimeout.CancelAfter(TimeSpan.FromSeconds(2));
            int n = await stream.ReadAsync(buffer, responseTimeout.Token);
            return DetectHttp(Encoding.ASCII.GetString(buffer, 0, n));
        }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        { return FtpServiceKind.Unknown; }
        catch (Exception ex) when (ex is IOException or SocketException or
            InvalidOperationException or ObjectDisposedException)
        { return FtpServiceKind.Unknown; }
    }

    internal static FtpServiceKind DetectHttp(string response)
    {
        if (!response.StartsWith("HTTP/", StringComparison.OrdinalIgnoreCase))
            return FtpServiceKind.Unknown;
        if (response.Contains("DAV-root", StringComparison.OrdinalIgnoreCase) ||
            response.Split('\n').Any(line => line.TrimStart().StartsWith("DAV:",
                StringComparison.OrdinalIgnoreCase)))
            return FtpServiceKind.HttpWebDav;
        return FtpServiceKind.HttpOther;
    }

    internal static string Explain(FtpServiceKind kind, string host, int port) => kind switch
    {
        FtpServiceKind.HttpWebDav =>
            $"FTP 연결 방식이 잘못됐습니다. {host}:{port}는 FTP가 아니라 WebDAV(HTTP) 서버로 응답합니다.\n" +
            "연결 설정을 'WebDAV', 현재 포트로 변경하고, HTTPS가 지원되는지 확인하세요.\n" +
            "HTTP만 지원하면 WebDAV 전송 항목에서 'HTTP 사용'을 명시적으로 선택할 수 있지만, " +
            "암호와 파일 내용이 암호화되지 않을 수 있습니다.",
        FtpServiceKind.HttpOther =>
            $"{host}:{port}는 FTP 환영 메시지 대신 HTTP 응답을 반환합니다. " +
            "RaiDrive에서 선택한 실제 프로토콜과 포트를 확인하세요.",
        _ => ""
    };
}
