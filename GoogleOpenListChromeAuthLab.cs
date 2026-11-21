using System.Diagnostics;
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
        var arguments = GoogleOpenListChromeAuth.ChromeArguments(
            "SYNTHETIC_APP_OWNED_PROFILE", 18222, GoogleOpenListChromeAuth.PublicBroker);
        if (!arguments.Contains("--new-window") ||
            !arguments.Contains("--user-data-dir=SYNTHETIC_APP_OWNED_PROFILE") ||
            arguments.Any(arg => arg.Contains("incognito", StringComparison.OrdinalIgnoreCase) ||
                arg.Contains("guest", StringComparison.OrdinalIgnoreCase) ||
                arg.Contains("headless", StringComparison.OrdinalIgnoreCase)))
            throw new IOException("Production Google login must use an ordinary isolated Chrome window");
        Console.WriteLine("GOOGLE_OPENLIST_NORMAL_CHROME_WINDOW_NO_INCOGNITO_PASS");

        string errorPayload = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            "\u000a\u000edeleted_client\u0012The OAuth client was deleted."))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var errorUrl = new Uri("https://accounts.google.com/signin/oauth/error?authError=" + errorPayload);
        if (GoogleOpenListChromeAuth.ReadAuthorizationErrorCode(errorUrl,
                GoogleOpenListChromeAuth.PublicBroker) != "deleted_client" ||
            GoogleOpenListChromeAuth.ReadAuthorizationErrorCode(new Uri(
                "https://untrusted.invalid/signin/oauth/error?error=deleted_client"),
                GoogleOpenListChromeAuth.PublicBroker) is not null)
            throw new IOException("Google terminal-error origin or payload classification failed");

        await RunScenarioAsync("success");
        await RunScenarioAsync("deleted_url");
        await RunScenarioAsync("deleted_document");
        await RunScenarioAsync("access_denied");
    }

    private static async Task RunScenarioAsync(string scenario)
    {
        using var reserve = new TcpListener(IPAddress.Loopback, 0);
        reserve.Start();
        int port = ((IPEndPoint)reserve.LocalEndpoint).Port;
        reserve.Stop();
        var origin = new Uri("http://127.0.0.1:" + port + "/");
        const string state = "FILEHUB_ISOLATED_OAUTH_TEST_1234567890";
        const string fakeAccess = "SYNTHETIC_OPENLIST_ACCESS_ONLY";
        const string fakeRefresh = "SYNTHETIC_OPENLIST_REFRESH_ONLY";
        string? expectedError = scenario switch
        {
            "deleted_url" or "deleted_document" => "deleted_client",
            "access_denied" => "access_denied",
            _ => null
        };
        string errorPayload = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            "\u000a\u000edeleted_client\u0012The OAuth client was deleted."))
            .TrimEnd('=').Replace('+', '-').Replace('/', '_');
        string brokerError = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            JsonSerializer.Serialize(new { message_err = "access_denied" })));
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
                            if (scenario == "deleted_url")
                                context.Response.Redirect(new Uri(origin,
                                    "signin/oauth/error?authError=" + errorPayload).AbsoluteUri);
                            else if (scenario == "deleted_document")
                            {
                                context.Response.StatusCode = 401;
                                context.Response.ContentType = "text/html";
                                await context.Response.OutputStream.WriteAsync(Encoding.UTF8.GetBytes(
                                    "<!doctype html><title>OAuth error</title><p>401: deleted_client</p>"));
                            }
                            else
                                context.Response.Redirect(new Uri(origin,
                                    "googleui/callback?" + (scenario == "access_denied"
                                        ? "error=access_denied" : "code=SYNTHETIC_CODE") +
                                    "&state=" + state).AbsoluteUri);
                        }
                        else if (path == "/signin/oauth/error")
                        {
                            context.Response.StatusCode = 401;
                            context.Response.ContentType = "text/html";
                            await context.Response.OutputStream.WriteAsync(Encoding.UTF8.GetBytes(
                                "<!doctype html><title>OAuth error</title><p>401: deleted_client</p>"));
                        }
                        else if (path == "/googleui/callback")
                        {
                            if (context.Request.QueryString["state"] != state)
                                throw new IOException("Isolated OAuth returned wrong state");
                            context.Response.Redirect(origin.AbsoluteUri + "#" +
                                (scenario == "access_denied" ? brokerError : encoded));
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
            // Prove that a successful /requests response cannot hide a
            // downstream Google rejection. This anonymous probe is local.
            try
            {
                await GoogleOpenListChromeAuth.CheckPublicProviderAsync(stop.Token, origin);
                if (expectedError is not null)
                    throw new IOException("Anonymous readiness probe accepted a rejected OAuth app");
            }
            catch (GoogleOpenListChromeAuth.AuthorizationException ex)
                when (expectedError is not null && ex.ErrorCode == expectedError) { }

            var timer = Stopwatch.StartNew();
            try
            {
                string token = await GoogleOpenListChromeAuth.AuthorizeAsync(
                    _ => { }, stop.Token, origin);
                if (expectedError is not null)
                    throw new IOException("Chrome accepted a terminal Google authorization error");
                using var doc = JsonDocument.Parse(token);
                if (doc.RootElement.GetProperty("access_token").GetString() != fakeAccess ||
                    doc.RootElement.GetProperty("refresh_token").GetString() != fakeRefresh)
                    throw new IOException("Chrome isolated OAuth round-trip returned wrong credentials");
                if (GoogleOpenListChromeAuth.ReadApprovedCallback(new Uri(
                    "https://accounts.google.com/#" + encoded), origin) is not null)
                    throw new IOException("Chrome callback accepted an untrusted origin");
            }
            catch (GoogleOpenListChromeAuth.AuthorizationException ex)
                when (expectedError is not null && ex.ErrorCode == expectedError)
            {
                if (timer.Elapsed > TimeSpan.FromSeconds(12) ||
                    ex.Message.Contains("SYNTHETIC", StringComparison.Ordinal))
                    throw new IOException("Chrome terminal error was delayed or disclosed callback data");
            }
            Console.WriteLine(scenario switch
            {
                "success" => "GOOGLE_OPENLIST_CHROME_SINGLE_LOGIN_NO_JSON_LOOPBACK_PASS",
                "deleted_url" => "GOOGLE_OPENLIST_DELETED_CLIENT_URL_PREFLIGHT_AND_CHROME_PASS",
                "deleted_document" => "GOOGLE_OPENLIST_DELETED_CLIENT_HTTP401_PREFLIGHT_AND_CHROME_PASS",
                _ => "GOOGLE_OPENLIST_ACCESS_DENIED_TERMINATES_BEFORE_CALLBACK_PASS"
            });
        }
        finally
        {
            stop.Cancel();
            server.Stop();
            await service;
        }
    }
}
