using System.Net;
using System.Net.Sockets;
using System.Text;

namespace NASMount;

internal static class FtpServiceDetectorLab
{
    internal static async Task RunAsync()
    {
        await Check("220 FTP test server ready\r\n", FtpServiceKind.Ftp,
            greetFirst: true);
        await Check("HTTP/1.1 401 Unauthorized\r\nServer: Apache\r\n" +
            "WWW-Authenticate: Basic realm=\"DAV-root\"\r\n\r\n",
            FtpServiceKind.HttpWebDav, greetFirst: false);
        await Check("HTTP/1.1 200 OK\r\nServer: Apache\r\n\r\n",
            FtpServiceKind.HttpOther, greetFirst: false);
    }

    private static async Task Check(string response, FtpServiceKind expected,
        bool greetFirst)
    {
        using var server = new TcpListener(IPAddress.Loopback, 0);
        server.Start();
        int port = ((IPEndPoint)server.LocalEndpoint).Port;
        Task serve = Task.Run(async () =>
        {
            using var client = await server.AcceptTcpClientAsync();
            await using var stream = client.GetStream();
            if (!greetFirst)
            {
                byte[] request = new byte[512];
                int count = await stream.ReadAsync(request);
                string firstLine = Encoding.ASCII.GetString(request, 0, count);
                if (!firstLine.StartsWith("OPTIONS / HTTP/1.1", StringComparison.Ordinal))
                    throw new InvalidOperationException("Unexpected HTTP probe request");
            }
            await stream.WriteAsync(Encoding.ASCII.GetBytes(response));
            await stream.FlushAsync();
        });
        FtpServiceKind actual = await FtpServiceDetector.CheckAsync("127.0.0.1", port);
        await serve.WaitAsync(TimeSpan.FromSeconds(6));
        if (actual != expected)
            throw new InvalidOperationException($"Expected {expected}, got {actual}");
    }
}
