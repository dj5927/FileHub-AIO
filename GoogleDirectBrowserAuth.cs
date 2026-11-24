using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Publisher-owned Google Desktop OAuth, normal browser + PKCE + loopback.
/// No browser profile, cookies, broker, rclone or user token is read here.
/// </summary>
internal static class GoogleDirectBrowserAuth
{
    private const string AuthorizationEndpoint =
        "https://accounts.google.com/o/oauth2/v2/auth";
    private static readonly Uri TokenEndpoint =
        new("https://oauth2.googleapis.com/token");
    internal const string DriveScope = "https://www.googleapis.com/auth/drive";
    internal const string EmailScope = "https://www.googleapis.com/auth/userinfo.email";
    internal const string RequestedScopes = EmailScope + " " + DriveScope;
    private static readonly Uri AccountEndpoint =
        new("https://www.googleapis.com/oauth2/v2/userinfo?fields=email,verified_email");
    private const int ResponseLimit = 256 * 1024;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    internal sealed class AuthorizationException : IOException
    {
        internal string ErrorCode { get; }
        internal AuthorizationException(string errorCode)
            : base(ErrorMessage(NormalizeError(errorCode)))
        {
            ErrorCode = NormalizeError(errorCode);
        }
    }

    private static string NormalizeError(string? code) => code switch
    {
        "deleted_client" or "invalid_client" or "unauthorized_client" or
        "redirect_uri_mismatch" or "access_denied" or "disallowed_useragent" or
        "admin_policy_enforced" or "org_internal" or "invalid_scope" or
        "invalid_request" or "invalid_grant" or "provider_unavailable" or
        "invalid_token_response" or "browser_launch_failed" or
        "drive_scope_not_granted" or "account_scope_not_granted" or
        "account_identity_unavailable" => code,
        _ => "authorization_error"
    };

    private static string ErrorMessage(string code) => code switch
    {
        "access_denied" => "Google 계정 접근 승인이 취소되었습니다.",
        "drive_scope_not_granted" =>
            "Google Drive 접근 권한이 선택되지 않았습니다. 연결하려면 Drive 권한을 선택하고 다시 승인하세요.",
        "account_scope_not_granted" =>
            "Google 계정 이메일 확인 권한이 승인되지 않았습니다. 연결할 계정을 확인하려면 다시 승인하세요.",
        "account_identity_unavailable" =>
            "Google 계정 이메일을 확인하지 못했습니다. 연결하지 않고 종료합니다.",
        "deleted_client" or "invalid_client" or "unauthorized_client" =>
            "FileHub 게시자의 Google 로그인 등록을 사용할 수 없습니다. 게시자 설정을 확인해야 합니다.",
        "browser_launch_failed" =>
            "Google 로그인 브라우저를 열 수 없습니다. 기본 브라우저 설정을 확인하세요.",
        "provider_unavailable" =>
            "Google 로그인 서버를 확인할 수 없습니다. 네트워크 연결을 확인하고 다시 시도하세요.",
        "invalid_token_response" =>
            "Google이 유효한 오프라인 로그인 정보를 반환하지 않았습니다. 다시 로그인하세요.",
        "admin_policy_enforced" or "org_internal" =>
            "Google 계정의 조직 정책 때문에 FileHub 접근을 승인할 수 없습니다.",
        _ => "Google 로그인을 완료할 수 없습니다. 오류 코드: " + code
    };

    private static string Base64Url(byte[] bytes) =>
        Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    internal static string BuildAuthorizationUrl(string clientId,
        string redirect, string verifier, string state)
    {
        if (string.IsNullOrWhiteSpace(clientId) ||
            !clientId.EndsWith(".apps.googleusercontent.com", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("A valid Google Desktop OAuth client is required");
        if (!Uri.TryCreate(redirect, UriKind.Absolute, out var uri) ||
            !uri.IsLoopback || uri.Scheme != "http" || uri.AbsolutePath != "/callback" ||
            uri.Query.Length != 0 || uri.Fragment.Length != 0 ||
            uri.UserInfo.Length != 0)
            throw new ArgumentException("OAuth redirect must be loopback /callback");
        var challenge = Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));
        string query = string.Join("&", new Dictionary<string, string>
        {
            ["client_id"] = clientId,
            ["redirect_uri"] = redirect,
            ["response_type"] = "code",
            ["scope"] = RequestedScopes,
            ["access_type"] = "offline",
            ["prompt"] = "select_account consent",
            ["code_challenge"] = challenge,
            ["code_challenge_method"] = "S256",
            ["state"] = state
        }.Select(p => Uri.EscapeDataString(p.Key) + "=" + Uri.EscapeDataString(p.Value)));
        return AuthorizationEndpoint + "?" + query;
    }

    private static bool TryDecode(string encoded, out string decoded)
    {
        decoded = "";
        var bytes = new byte[encoded.Length];
        int count = 0;
        for (int i = 0; i < encoded.Length; i++)
        {
            char value = encoded[i];
            if (value == '%')
            {
                if (i + 2 >= encoded.Length ||
                    !byte.TryParse(encoded.AsSpan(i + 1, 2),
                        System.Globalization.NumberStyles.HexNumber,
                        System.Globalization.CultureInfo.InvariantCulture, out byte octet))
                    return false;
                bytes[count++] = octet;
                i += 2;
            }
            else
            {
                if (value > 127 || char.IsControl(value)) return false;
                bytes[count++] = value == '+' ? (byte)' ' : (byte)value;
            }
        }
        try { decoded = StrictUtf8.GetString(bytes, 0, count); }
        catch (DecoderFallbackException) { return false; }
        return !decoded.Any(char.IsControl);
    }

    private static bool TryQuery(string query, int limit,
        out Dictionary<string, string> values)
    {
        values = new(StringComparer.Ordinal);
        if (query.Length > limit) return false;
        string content = query.StartsWith('?') ? query[1..] : query;
        if (content.Length == 0) return true;
        foreach (string part in content.Split('&'))
        {
            int separator = part.IndexOf('=');
            if (separator <= 0 ||
                !TryDecode(part[..separator], out string key) ||
                !TryDecode(part[(separator + 1)..], out string value) ||
                key.Length == 0 || !values.TryAdd(key, value))
                return false;
        }
        return true;
    }

    private enum CallbackKind { Ignore, Approved, Rejected }

    private static CallbackKind ParseCallback(string path, string query,
        string expectedState, out string? code, out string? error)
    {
        code = null;
        error = null;
        if (path != "/callback" || !TryQuery(query, 8192, out var parts) ||
            !parts.TryGetValue("state", out var supplied) ||
            !CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(Encoding.UTF8.GetBytes(expectedState)),
                SHA256.HashData(Encoding.UTF8.GetBytes(supplied))))
            return CallbackKind.Ignore;
        // An error is authoritative only after the same state check as a code.
        // Ambiguous, duplicated or malformed callbacks never end the real flow.
        if (parts.TryGetValue("error", out string? rejection))
        {
            if (rejection.Length == 0 || parts.ContainsKey("code"))
                return CallbackKind.Ignore;
            error = NormalizeError(rejection);
            return CallbackKind.Rejected;
        }
        if (!parts.TryGetValue("code", out var grant) ||
            string.IsNullOrWhiteSpace(grant) || grant.Length > 4096)
            return CallbackKind.Ignore;
        code = grant;
        return CallbackKind.Approved;
    }

    internal static bool ValidCallback(string path, string query, string expectedState,
        out string? code) =>
        ParseCallback(path, query, expectedState, out code, out _) == CallbackKind.Approved;

    private static async Task<byte[]> ReadBoundedAsync(HttpContent content,
        string errorCode, CancellationToken cancel)
    {
        if (content.Headers.ContentLength is long length && length > ResponseLimit)
            throw new AuthorizationException(errorCode);
        await using var stream = await content.ReadAsStreamAsync(cancel).ConfigureAwait(false);
        using var result = new MemoryStream();
        byte[] buffer = new byte[8192];
        while (true)
        {
            int size = await stream.ReadAsync(buffer, cancel).ConfigureAwait(false);
            if (size == 0) return result.ToArray();
            if (result.Length + size > ResponseLimit)
                throw new AuthorizationException(errorCode);
            result.Write(buffer, 0, size);
        }
    }

    private static string? KnownProviderError(string value)
    {
        foreach (string code in new[] { "deleted_client", "invalid_client",
            "unauthorized_client", "redirect_uri_mismatch", "access_denied",
            "disallowed_useragent", "admin_policy_enforced", "org_internal",
            "invalid_scope", "invalid_request", "invalid_grant" })
        {
            if (value.Contains(code, StringComparison.Ordinal)) return code;
        }
        return null;
    }

    private static string? ReadinessError(Uri uri)
    {
        if (!TryQuery(uri.Query, 32768, out var query))
            return "authorization_error";
        if (query.TryGetValue("error", out var error))
            return NormalizeError(error);
        if (query.TryGetValue("authError", out var encoded))
        {
            if (encoded.Length > 16384) return "authorization_error";
            try
            {
                string base64 = encoded.Replace('-', '+').Replace('_', '/');
                base64 = base64.PadRight((base64.Length + 3) / 4 * 4, '=');
                return KnownProviderError(Encoding.UTF8.GetString(
                    Convert.FromBase64String(base64))) ?? "authorization_error";
            }
            catch (FormatException) { return "authorization_error"; }
        }
        return null;
    }

    private static bool IsGoogleAuthorizationUri(Uri uri) =>
        uri.Scheme == Uri.UriSchemeHttps && uri.Port == 443 &&
        uri.Host.Equals("accounts.google.com", StringComparison.OrdinalIgnoreCase) &&
        uri.UserInfo.Length == 0;

    /// <summary>
    /// Anonymous authorization-entry check, not an account approval. The fresh
    /// cookie jar is discarded; no user's existing browser session is consulted.
    /// The optional client is exclusively an in-memory test seam.
    /// </summary>
    internal static async Task CheckPublisherAsync(string clientId, string clientSecret,
        CancellationToken cancel = default, HttpClient? probeClient = null)
    {
        GoogleOAuthClientFile.ValidateDesktopCredentials(clientId, clientSecret);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        using var owned = probeClient is null ? new HttpClient(new HttpClientHandler
        {
            AllowAutoRedirect = false,
            UseCookies = true,
            CookieContainer = new CookieContainer(),
            UseDefaultCredentials = false
        }) { Timeout = Timeout.InfiniteTimeSpan } : null;
        var client = probeClient ?? owned!;
        // Google Desktop clients permit a dynamic loopback port. Do not open a
        // browser or send the client secret during this public-entry check.
        Uri next = new(BuildAuthorizationUrl(clientId,
            "http://127.0.0.1:49152/callback",
            Base64Url(RandomNumberGenerator.GetBytes(32)),
            Base64Url(RandomNumberGenerator.GetBytes(24))));
        try
        {
            for (int step = 0; step < 9; step++)
            {
                if (!IsGoogleAuthorizationUri(next))
                    throw new AuthorizationException("provider_unavailable");
                string? error = ReadinessError(next);
                if (error is not null) throw new AuthorizationException(error);
                using var request = new HttpRequestMessage(HttpMethod.Get, next);
                using var response = await client.SendAsync(request,
                    HttpCompletionOption.ResponseHeadersRead, deadline.Token)
                    .ConfigureAwait(false);
                if ((int)response.StatusCode is >= 300 and < 400)
                {
                    var location = response.Headers.Location;
                    if (location is null)
                        throw new AuthorizationException("provider_unavailable");
                    next = location.IsAbsoluteUri ? location : new Uri(next, location);
                    continue;
                }
                if (next.AbsolutePath.Contains("/oauth/error", StringComparison.Ordinal) ||
                    !response.IsSuccessStatusCode)
                {
                    byte[] body = await ReadBoundedAsync(response.Content,
                        "provider_unavailable", deadline.Token).ConfigureAwait(false);
                    throw new AuthorizationException(
                        KnownProviderError(Encoding.UTF8.GetString(body)) ??
                        (response.IsSuccessStatusCode ? "authorization_error" :
                            "provider_unavailable"));
                }
                // A Google sign-in page can contain a large JavaScript bundle.
                // Recognize only its HTTPS sign-in route from response headers;
                // do not buffer the account form, or mistake arbitrary HTTP 200
                // at an OAuth endpoint for an available login.
                string path = next.AbsolutePath;
                bool signIn = path.StartsWith("/v3/signin/", StringComparison.Ordinal) ||
                    path.StartsWith("/v2/identifier", StringComparison.Ordinal) ||
                    path.StartsWith("/signin/", StringComparison.Ordinal) ||
                    path.Equals("/ServiceLogin", StringComparison.Ordinal);
                if (response.StatusCode != HttpStatusCode.OK || !signIn)
                    throw new AuthorizationException("provider_unavailable");
                return;
            }
            throw new AuthorizationException("provider_unavailable");
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        { throw new AuthorizationException("provider_unavailable"); }
        catch (HttpRequestException)
        { throw new AuthorizationException("provider_unavailable"); }
    }

    private static async Task<string> ExchangeAuthorizationCodeAsync(
        HttpClient client, string code, string clientId, string clientSecret,
        string redirect, string verifier, CancellationToken cancel)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(20));
        using var request = new HttpRequestMessage(HttpMethod.Post, TokenEndpoint)
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["code"] = code,
                ["client_id"] = clientId,
                ["client_secret"] = clientSecret,
                ["redirect_uri"] = redirect,
                ["grant_type"] = "authorization_code",
                ["code_verifier"] = verifier
            })
        };
        try
        {
            using var response = await client.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            byte[] body = await ReadBoundedAsync(response.Content,
                "invalid_token_response", deadline.Token).ConfigureAwait(false);
            using var document = JsonDocument.Parse(body);
            var value = document.RootElement;
            if (value.ValueKind != JsonValueKind.Object)
                throw new AuthorizationException("invalid_token_response");
            if (!response.IsSuccessStatusCode)
            {
                string error = value.TryGetProperty("error", out var failure) &&
                    failure.ValueKind == JsonValueKind.String
                    ? NormalizeError(failure.GetString()) : "authorization_error";
                throw new AuthorizationException(error);
            }
            if (!value.TryGetProperty("access_token", out var access) ||
                access.ValueKind != JsonValueKind.String ||
                string.IsNullOrWhiteSpace(access.GetString()))
                throw new AuthorizationException("invalid_token_response");
            return value.GetRawText();
        }
        catch (JsonException)
        { throw new AuthorizationException("invalid_token_response"); }
        catch (HttpRequestException)
        { throw new AuthorizationException("provider_unavailable"); }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        { throw new AuthorizationException("provider_unavailable"); }
    }

    private static HashSet<string> GrantedScopes(JsonElement token)
    {
        if (!token.TryGetProperty("scope", out var scope) ||
            scope.ValueKind != JsonValueKind.String ||
            string.IsNullOrWhiteSpace(scope.GetString()))
            throw new AuthorizationException("invalid_token_response");
        return scope.GetString()!.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .ToHashSet(StringComparer.Ordinal);
    }

    private static async Task<string> ReadAccountEmailAsync(HttpClient client,
        JsonElement token, CancellationToken cancel)
    {
        var scopes = GrantedScopes(token);
        if (!scopes.Contains(EmailScope) && !scopes.Contains("email"))
            throw new AuthorizationException("account_scope_not_granted");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        using var request = new HttpRequestMessage(HttpMethod.Get, AccountEndpoint);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue(
            "Bearer", token.GetProperty("access_token").GetString());
        try
        {
            using var response = await client.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new AuthorizationException("account_identity_unavailable");
            byte[] body = await ReadBoundedAsync(response.Content,
                "account_identity_unavailable", deadline.Token).ConfigureAwait(false);
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            string? email = root.ValueKind == JsonValueKind.Object &&
                root.TryGetProperty("email", out var value) &&
                value.ValueKind == JsonValueKind.String ? value.GetString() : null;
            if (string.IsNullOrWhiteSpace(email) || email.Length > 320 ||
                !email.Contains('@') || email.Any(c => char.IsControl(c) || char.IsWhiteSpace(c)) ||
                !root.TryGetProperty("verified_email", out var verified) ||
                verified.ValueKind != JsonValueKind.True)
                throw new AuthorizationException("account_identity_unavailable");
            return email;
        }
        catch (JsonException)
        { throw new AuthorizationException("account_identity_unavailable"); }
        catch (HttpRequestException)
        { throw new AuthorizationException("account_identity_unavailable"); }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        { throw new AuthorizationException("account_identity_unavailable"); }
    }

    private static void RequireDriveGrant(JsonElement token)
    {
        if (!GrantedScopes(token).Contains(DriveScope))
            throw new AuthorizationException("drive_scope_not_granted");
        if (!token.TryGetProperty("refresh_token", out var refresh) ||
            refresh.ValueKind != JsonValueKind.String ||
            string.IsNullOrWhiteSpace(refresh.GetString()))
            throw new AuthorizationException("invalid_token_response");
    }

    private static async Task<string?> ReadRequestLineAsync(NetworkStream stream,
        CancellationToken cancel)
    {
        byte[] bytes = new byte[8500];
        int total = 0;
        while (total < bytes.Length)
        {
            int count = await stream.ReadAsync(bytes.AsMemory(total,
                Math.Min(1024, bytes.Length - total)), cancel).ConfigureAwait(false);
            if (count == 0) return null;
            int newline = Array.IndexOf(bytes, (byte)'\n', total, count);
            total += count;
            if (newline < 0) continue;
            int length = newline > 0 && bytes[newline - 1] == '\r' ? newline - 1 : newline;
            for (int i = 0; i < length; i++)
                if (bytes[i] < 32 || bytes[i] >= 127) return null;
            return Encoding.ASCII.GetString(bytes, 0, length);
        }
        return null;
    }

    private static void RequestTarget(string? firstLine, out string path, out string query)
    {
        path = "";
        query = "";
        if (firstLine is null) return;
        string[] parts = firstLine.Split(' ');
        if (parts.Length != 3 || parts[0] != "GET" ||
            (parts[2] != "HTTP/1.1" && parts[2] != "HTTP/1.0")) return;
        string target = parts[1];
        if (!target.StartsWith('/') || target.StartsWith("//", StringComparison.Ordinal) ||
            target.Contains('#') || target.Contains('\\')) return;
        int separator = target.IndexOf('?');
        path = separator < 0 ? target : target[..separator];
        query = separator < 0 ? "" : target[separator..];
    }

    private static async Task WriteCallbackAsync(NetworkStream stream, bool success,
        bool denied, bool failed, CancellationToken cancel,
        string? accountEmail = null, bool driveNotGranted = false)
    {
        string message = success ? "Google 로그인 완료. 이 창을 닫아도 됩니다." :
            driveNotGranted ? "Google 계정은 확인됐지만 Drive 접근 권한을 선택하지 않았습니다. 연결하지 않았습니다." :
            denied ? "Google 접근 승인이 취소되었습니다. FileHub로 돌아가세요." :
            failed ? "Google 인증을 완료하지 못했습니다. FileHub로 돌아가 다시 시도하세요." :
            "인증 응답이 올바르지 않습니다. 진행 중인 Google 승인 창으로 돌아가세요.";
        byte[] output = Encoding.UTF8.GetBytes(
            "<!doctype html><meta charset=utf-8><title>FileHub AIO</title><p>" +
            message + "</p>" + (accountEmail is null ? "" :
                "<p>Google 계정: " + WebUtility.HtmlEncode(accountEmail) + "</p>"));
        byte[] header = Encoding.ASCII.GetBytes(
            $"HTTP/1.1 {(success ? "200 OK" : "400 Bad Request")}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n" +
            $"Content-Length: {output.Length}\r\n\r\n");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        try
        {
            await stream.WriteAsync(header, deadline.Token).ConfigureAwait(false);
            await stream.WriteAsync(output, deadline.Token).ConfigureAwait(false);
        }
        // A closed browser connection cannot invalidate an already committed
        // grant, conceal an OAuth error, or abort a genuine pending approval.
        catch (IOException) { }
        catch (OperationCanceledException) { }
    }

    internal static async Task<string> AuthorizeAsync(string clientId,
        string clientSecret, Action<string> progress,
        Action<string> openBrowser, CancellationToken cancel,
        HttpClient? tokenClient = null,
        Func<string, CancellationToken, Task>? finalizeGrant = null)
    {
        GoogleOAuthClientFile.ValidateDesktopCredentials(clientId, clientSecret);
        if (tokenClient is null)
        {
            progress("FileHub의 Google 로그인 등록을 확인하고 있습니다.");
            await CheckPublisherAsync(clientId, clientSecret, cancel).ConfigureAwait(false);
        }
        cancel.ThrowIfCancellationRequested();
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start(8);
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
            ? new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
                { Timeout = Timeout.InfiniteTimeSpan } : null;
        var client = tokenClient ?? ownedClient!;
        while (true)
        {
            using var browser = await listener.AcceptTcpClientAsync(deadline.Token)
                .ConfigureAwait(false);
            using NetworkStream stream = browser.GetStream();
            using var callbackDeadline =
                CancellationTokenSource.CreateLinkedTokenSource(deadline.Token);
            callbackDeadline.CancelAfter(TimeSpan.FromSeconds(5));
            string? firstLine;
            try
            {
                firstLine = await ReadRequestLineAsync(stream, callbackDeadline.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!deadline.IsCancellationRequested)
            { continue; }
            catch (IOException) { continue; }
            RequestTarget(firstLine, out string path, out string query);
            CallbackKind kind = ParseCallback(path, query, state,
                out string? grant, out string? rejection);
            string? tokenJson = null;
            string? accountEmail = null;
            Exception? failure = kind == CallbackKind.Rejected
                ? new AuthorizationException(rejection!) : null;
            if (kind == CallbackKind.Approved)
            {
                progress("Google 인증 정보를 안전하게 확인하고 있습니다.");
                try
                {
                    tokenJson = await ExchangeAuthorizationCodeAsync(client,
                        grant!, clientId, clientSecret, redirect, verifier,
                        deadline.Token).ConfigureAwait(false);
                    // Identity is independently useful even when Drive is declined.
                    // Only the verified account email is displayed, never persisted/logged.
                    using (var token = JsonDocument.Parse(tokenJson))
                    {
                        accountEmail = await ReadAccountEmailAsync(client, token.RootElement,
                            deadline.Token).ConfigureAwait(false);
                        progress("Google 계정 확인: " + accountEmail);
                        RequireDriveGrant(token.RootElement);
                    }
                    if (finalizeGrant is not null)
                        await finalizeGrant(tokenJson, deadline.Token).ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or HttpRequestException
                    or JsonException or OperationCanceledException or
                    UnauthorizedAccessException or CryptographicException)
                {
                    tokenJson = null;
                    failure = ex;
                }
            }
            await WriteCallbackAsync(stream, tokenJson is not null,
                rejection == "access_denied", failure is not null, deadline.Token,
                accountEmail, failure is AuthorizationException { ErrorCode: "drive_scope_not_granted" })
                .ConfigureAwait(false);
            if (failure is not null) throw failure;
            if (tokenJson is not null) return tokenJson; // Never log or print.
        }
    }

    internal static void PolicyTest()
    {
        string id = "123456789012-test-desktop.apps.googleusercontent.com";
        string url = BuildAuthorizationUrl(id,
            "http://127.0.0.1:45678/callback", "verifier", "STATE");
        if (!url.Contains("code_challenge_method=S256", StringComparison.Ordinal) ||
            !url.Contains("scope=" + Uri.EscapeDataString(RequestedScopes), StringComparison.Ordinal) ||
            !url.Contains("redirect_uri=", StringComparison.Ordinal) ||
            !url.Contains("access_type=offline", StringComparison.Ordinal) ||
            !url.Contains("prompt=select_account%20consent", StringComparison.Ordinal) ||
            !ValidCallback("/callback", "?code=VALID&state=STATE", "STATE", out var code) ||
            code != "VALID" ||
            ValidCallback("/callback", "?code=VALID&state=WRONG", "STATE", out _) ||
            ValidCallback("/other", "?code=VALID&state=STATE", "STATE", out _) ||
            ValidCallback("/callback", "?code=VALID&state=STATE&state=STATE", "STATE", out _) ||
            ValidCallback("/callback", "?code=%FF&state=STATE", "STATE", out _) ||
            ValidCallback("/callback", "?code=%GG&state=STATE", "STATE", out _) ||
            ValidCallback("/callback", "?code=VALID&error=access_denied&state=STATE", "STATE", out _) ||
            ParseCallback("/callback", "?error=access_denied&state=WRONG",
                "STATE", out _, out _) != CallbackKind.Ignore ||
            ParseCallback("/callback", "?error=access_denied&state=STATE",
                "STATE", out _, out string? error) != CallbackKind.Rejected ||
            error != "access_denied")
            throw new InvalidOperationException("Native Google OAuth PKCE/CSRF policy failed");
        Console.WriteLine("GOOGLE_NATIVE_BROWSER_PKCE_OAUTH_POLICY_PASS");
    }
}
