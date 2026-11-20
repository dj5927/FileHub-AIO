using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Native Google Desktop OAuth Authorization Code + PKCE.
/// Opens the normal system browser (Chrome when installed as default).
/// rclone is NEVER launched for authentication or token exchange.
/// Requires a Google Desktop OAuth client owned by this application's
/// publisher; a browser login is not possible without a valid client ID.
/// </summary>
internal static class GoogleDirectBrowserAuth
{
    private const string AuthorizationEndpoint =
        "https://accounts.google.com/o/oauth2/v2/auth";
    private static readonly Uri TokenEndpoint =
        new("https://oauth2.googleapis.com/token");

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    internal static string BuildAuthorizationUrl(string clientId,
        string redirect, string verifier, string state)
    {
        if (string.IsNullOrWhiteSpace(clientId) ||
            !clientId.EndsWith(".apps.googleusercontent.com", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("A valid Google Desktop OAuth client is required");
        if (!Uri.TryCreate(redirect, UriKind.Absolute, out var uri) ||
            !uri.IsLoopback || uri.Scheme != "http" || uri.AbsolutePath != "/callback")
            throw new ArgumentException("OAuth redirect must be loopback /callback");
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        string query = string.Join("&", new Dictionary<string, string>
        {
            ["client_id"] = clientId,
            ["redirect_uri"] = redirect,
            ["response_type"] = "code",
            ["scope"] = "https://www.googleapis.com/auth/drive",
            ["access_type"] = "offline",
            ["prompt"] = "consent",
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
            ["state"] = state
        }.Select(p => Uri.EscapeDataString(p.Key) + "=" + Uri.EscapeDataString(p.Value)));
        return AuthorizationEndpoint + "?" + query;
    }

    internal static bool ValidCallback(string path, string query, string expectedState,
        out string? code)
    {
        code = null;
        if (path != "/callback" || query.Length > 8192) return false;
        var parts = query.TrimStart('?').Split('&',
            StringSplitOptions.RemoveEmptyEntries)
            .Select(part => part.Split('=', 2))
            .Where(part => part.Length == 2)
            .ToDictionary(part => Uri.UnescapeDataString(part[0]),
                part => Uri.UnescapeDataString(part[1]), StringComparer.Ordinal);
        if (!parts.TryGetValue("state", out var supplied) ||
            !CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(Encoding.UTF8.GetBytes(expectedState)),
                SHA256.HashData(Encoding.UTF8.GetBytes(supplied))))
            return false;
        if (parts.ContainsKey("error")) return false;
        if (!parts.TryGetValue("code", out var grant) ||
            grant.Length == 0 || grant.Length > 4096) return false;
        code = grant;
        return true;
    }

    private static async Task<string> ExchangeAuthorizationCodeAsync(
        HttpClient client, string code, string clientId, string clientSecret,
        string redirect, string verifier, CancellationToken cancel)
    {
        using var form = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["code"] = code,
            ["client_id"] = clientId,
            ["client_secret"] = clientSecret,
            ["redirect_uri"] = redirect,
            ["grant_type"] = "authorization_code",
            ["code_verifier"] = verifier
        });
        using var response = await client.PostAsync(TokenEndpoint, form, cancel)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
            throw new IOException("Google OAuth authorization HTTP " +
                (int)response.StatusCode);
        using var document = await JsonDocument.ParseAsync(
            await response.Content.ReadAsStreamAsync(cancel),
            cancellationToken: cancel).ConfigureAwait(false);
        var value = document.RootElement;
        if (!value.TryGetProperty("access_token", out var access) ||
            string.IsNullOrWhiteSpace(access.GetString()) ||
            !value.TryGetProperty("refresh_token", out var refresh) ||
            string.IsNullOrWhiteSpace(refresh.GetString()))
            throw new IOException("Google authorization returned no offline refresh grant");
        return value.GetRawText();
    }

    internal static async Task<string> AuthorizeAsync(string clientId,
        string clientSecret, Action<string> progress,
        Action<string> openBrowser, CancellationToken cancel,
        HttpClient? tokenClient = null)
    {
        GoogleOAuthClientFile.ValidateDesktopCredentials(clientId, clientSecret);
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(1);
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        string redirect = $"http://127.0.0.1:{port}/callback";
        string verifier = Base64Url(RandomNumberGenerator.GetBytes(32));
        string state = Base64Url(RandomNumberGenerator.GetBytes(24));
        string authorization = BuildAuthorizationUrl(clientId, redirect, verifier, state);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromMinutes(4));
        openBrowser(authorization);
        progress("Chrome에서 Google 계정을 선택하고 접근을 승인하세요.");
        using var ownedClient = tokenClient is null
            ? new HttpClient { Timeout = TimeSpan.FromSeconds(20) } : null;
        var client = tokenClient ?? ownedClient!;
        string? tokenJson = null;
        while (!deadline.IsCancellationRequested && tokenJson is null)
        {
            using var browser = await listener.AcceptTcpClientAsync(deadline.Token)
                .ConfigureAwait(false);
            using NetworkStream stream = browser.GetStream();
            stream.ReadTimeout = 5000;
            using var reader = new StreamReader(stream, Encoding.ASCII,
                detectEncodingFromByteOrderMarks: false, bufferSize: 4096,
                leaveOpen: true);
            // A local process can connect and never send a GET. Cap each
            // callback's header wait independently so it cannot keep the
            // real browser waiting for the entire four-minute login window.
            using var callbackDeadline =
                CancellationTokenSource.CreateLinkedTokenSource(deadline.Token);
            callbackDeadline.CancelAfter(TimeSpan.FromSeconds(5));
            string? firstLine;
            try
            {
                firstLine = await reader.ReadLineAsync(callbackDeadline.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!deadline.IsCancellationRequested)
            { continue; }
            string requestedPath = "";
            string query = "";
            if (firstLine is not null && firstLine.Length < 8500 &&
                firstLine.StartsWith("GET ", StringComparison.Ordinal) &&
                Uri.TryCreate("http://127.0.0.1" +
                    firstLine.Split(' ', StringSplitOptions.RemoveEmptyEntries)[1],
                    UriKind.Absolute, out var candidate))
            {
                requestedPath = candidate.AbsolutePath;
                query = candidate.Query;
            }
            bool valid = false;
            string? grant = null;
            try { valid = ValidCallback(requestedPath, query, state, out grant); }
            catch (ArgumentException) { grant = null; }
            Exception? exchangeError = null;
            if (valid && grant is not null)
            {
                progress("Google 인증 정보를 안전하게 확인하고 있습니다.");
                try
                {
                    // Never show success until the Google token exchange
                    // has actually granted the FileHub session.
                    tokenJson = await ExchangeAuthorizationCodeAsync(client,
                        grant, clientId, clientSecret, redirect, verifier,
                        deadline.Token).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or HttpRequestException
                    or JsonException or TaskCanceledException)
                { exchangeError = ex; }
            }
            string html = tokenJson is not null
                ? "<!doctype html><meta charset=utf-8><title>FileHub AIO</title>" +
                  "<p>Google 로그인 완료. 이 창을 닫아도 됩니다.</p>"
                : exchangeError is not null
                ? "<!doctype html><meta charset=utf-8><title>FileHub AIO</title>" +
                  "<p>Google 인증을 완료하지 못했습니다. FileHub로 돌아가 다시 시도하세요.</p>"
                : "<!doctype html><meta charset=utf-8><title>FileHub AIO</title>" +
                  "<p>인증 응답이 올바르지 않습니다. 프로그램으로 돌아가세요.</p>";
            byte[] output = Encoding.UTF8.GetBytes(html);
            byte[] header = Encoding.ASCII.GetBytes(
                $"HTTP/1.1 {(tokenJson is not null ? "200 OK" : "400 Bad Request")}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n" +
                $"Content-Length: {output.Length}\r\n\r\n");
            await stream.WriteAsync(header, deadline.Token).ConfigureAwait(false);
            await stream.WriteAsync(output, deadline.Token).ConfigureAwait(false);
            if (exchangeError is not null) throw exchangeError;
        }
        if (string.IsNullOrEmpty(tokenJson))
            throw new OperationCanceledException("Google OAuth approval timed out");
        return tokenJson; // Never log or print.
    }

    internal static void PolicyTest()
    {
        string id = "123456789012-test-desktop.apps.googleusercontent.com";
        string url = BuildAuthorizationUrl(id,
            "http://127.0.0.1:45678/callback", "verifier", "STATE");
        if (!url.Contains("code_challenge_method=S256", StringComparison.Ordinal) ||
            !url.Contains("redirect_uri=", StringComparison.Ordinal) ||
            !url.Contains("access_type=offline", StringComparison.Ordinal) ||
            !ValidCallback("/callback", "?code=VALID&state=STATE", "STATE", out var code) ||
            code != "VALID" ||
            ValidCallback("/callback", "?code=VALID&state=WRONG", "STATE", out _) ||
            ValidCallback("/other", "?code=VALID&state=STATE", "STATE", out _))
            throw new InvalidOperationException("Native Google OAuth PKCE/CSRF policy failed");
        Console.WriteLine("GOOGLE_NATIVE_BROWSER_PKCE_OAUTH_POLICY_PASS");
    }
}
