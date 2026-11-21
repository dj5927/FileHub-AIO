using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>Real Chrome DevTools handshake and OAuth callback flow over
/// isolated loopback HTTP only. Google is never contacted in this test.</summary>
internal static class GoogleOpenListChromeAuthLab
{
    internal static async Task RunAsync()
    {
        using var reserve = new TcpListener(IPAddress.Loopback, 0);
        reserve.Start();
        int port = ((IPEndPoint)reserve.LocalEndpoint).Port;
        reserve.Stop();
        var origin = new Uri("http://127.0.0.1:" + port + "/");
        const string state = "FILEHUB_ISOLATED_OAUTH_TEST_1234567890";
        const string fakeAccess = "SYNTHETIC_OPENLIST_ACCESS_ONLY";
        const string fakeRefresh = "SYNTHETIC_OPENLIST_REFRESH_ONLY";
        string encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            JsonSerializer.Serialize(new
            {
                server_use = "true", driver_txt = "googleui_go",
                access_token = fakeAccess, refresh_token = fakeRefresh
            })));
        using var server = new HttpListener();
        server.Prefixes.Add(origin.AbsoluteUri);
        server.Start();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(18));
        var service = Task.Run(async () =>
        {
            try
            {
                while (!stop.IsCancellationRequested)
                {
                    var context = await server.GetContextAsync()
                        .WaitAsync(stop.Token);
                    try
                    {
                        string path = context.Request.Url!.AbsolutePath;
                        if (path == "/googleui/requests")
                        {
                            string auth = new Uri(origin, "authorize?" +
                                "client_id=SYNTHETIC.apps.googleusercontent.com" +
                                "&scope=" + Uri.EscapeDataString(
                                    "https://www.googleapis.com/auth/drive") +
                                "&response_type=code&state=" + state +
                                "&redirect_uri=" + Uri.EscapeDataString(
                                    new Uri(origin, "googleui/callback").AbsoluteUri))
                                .AbsoluteUri;
                            context.Response.ContentType = "application/json";
                            var bytes = Encoding.UTF8.GetBytes(
                                JsonSerializer.Serialize(new { text = auth }));
                            await context.Response.OutputStream.WriteAsync(bytes);
                        }
                        else if (path == "/authorize")
                        {
                            context.Response.Redirect(new Uri(origin,
                                "googleui/callback?code=SYNTHETIC_CODE&state=" + state)
                                .AbsoluteUri);
                        }
                        else if (path == "/googleui/callback")
                        {
                            if (context.Request.QueryString["state"] != state)
                                throw new IOException("Isolated OAuth returned wrong state");
                            context.Response.Redirect(origin.AbsoluteUri + "#" + encoded);
                        }
                        else if (path == "/")
                        {
                            var body = Encoding.UTF8.GetBytes(
                                "<!doctype html><title>OAuth done</title><p>Chrome mock complete</p>");
                            context.Response.ContentType = "text/html";
                            await context.Response.OutputStream.WriteAsync(body);
                        }
                        else context.Response.StatusCode = 404;
                    }
                    finally { context.Response.Close(); }
                }
            }
            catch (Exception ex) when (ex is OperationCanceledException or
                HttpListenerException or ObjectDisposedException) { }
        });
        try
        {
            string token = await GoogleOpenListChromeAuth.AuthorizeAsync(
                _ => { }, stop.Token, origin);
            using var doc = JsonDocument.Parse(token);
            if (doc.RootElement.GetProperty("access_token").GetString() != fakeAccess ||
                doc.RootElement.GetProperty("refresh_token").GetString() != fakeRefresh)
                throw new IOException("Chrome isolated OAuth round-trip returned wrong credentials");
            if (GoogleOpenListChromeAuth.ReadApprovedCallback(new Uri(
                "https://accounts.google.com/#" + encoded), origin) is not null)
                throw new IOException("Chrome callback accepted an untrusted origin");
            Console.WriteLine("GOOGLE_OPENLIST_CHROME_SINGLE_LOGIN_NO_JSON_LOOPBACK_PASS");
        }
        finally
        {
            stop.Cancel();
            server.Stop();
            await service;
        }
    }
}
