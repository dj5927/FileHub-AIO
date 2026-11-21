using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Optional OpenList public OAuth broker: the Google consent screen explicitly
/// identifies OpenList. This is NOT FileHub's own OAuth client; users approve
/// the named provider in Chrome. FileHub directly accesses Google Drive after
/// that consent, without rclone or extra copy/paste/JSON steps.
/// The dedicated Chrome window uses a disposable, isolated debug profile so
/// FileHub can collect the public broker's OAuth callback fragment exactly
/// once, then closes and removes its temporary browser profile.
/// </summary>
internal static class GoogleOpenListChromeAuth
{
    internal static readonly Uri PublicBroker = new("https://api.oplist.org/");
    private const int MaxMessageBytes = 1024 * 1024;
    private static readonly string[] GoogleErrorCodes =
    [
        "deleted_client", "invalid_client", "unauthorized_client",
        "redirect_uri_mismatch", "access_denied", "disallowed_useragent",
        "admin_policy_enforced", "org_internal", "invalid_scope", "invalid_request"
    ];

    internal sealed class AuthorizationException : IOException
    {
        internal string ErrorCode { get; }
        internal AuthorizationException(string errorCode) : base(ErrorMessage(errorCode)) =>
            ErrorCode = errorCode;
    }

    private static string ErrorMessage(string code) => code switch
    {
        "deleted_client" => "OpenList가 사용하는 Google 로그인 앱이 삭제되어 승인이 차단됐습니다. OpenList 로그인 서버의 OAuth 설정을 복구해야 합니다. 시크릿 모드나 계정 비밀번호 문제는 아닙니다.",
        "invalid_client" or "unauthorized_client" => "OpenList의 Google 로그인 앱 설정이 유효하지 않아 승인이 차단됐습니다. 로그인 서버의 OAuth 설정을 확인해야 합니다.",
        "redirect_uri_mismatch" => "OpenList의 Google 승인 반환 주소가 일치하지 않습니다. 로그인 서버의 OAuth 설정을 수정해야 합니다.",
        "access_denied" => "Google Drive 접근 승인이 거부되거나 취소됐습니다.",
        "disallowed_useragent" => "Google이 현재 브라우저 로그인 방식을 허용하지 않았습니다.",
        "admin_policy_enforced" or "org_internal" => "이 Google 계정의 조직 정책으로 OpenList 접근이 허용되지 않습니다.",
        "provider_unavailable" => "Google 로그인 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
        "browser_connection_lost" => "Google 로그인 창이 닫혔거나 연결이 끊겼습니다. 계정 연결을 다시 눌러 주세요.",
        "authorization_timeout" => "Google 계정 승인 대기 시간이 끝났습니다. 계정 연결을 다시 눌러 주세요.",
        _ => "Google이 로그인 요청을 거부했습니다. 로그인 제공자의 OAuth 설정을 확인해야 합니다."
    };

    private static bool SameOrigin(Uri first, Uri second) =>
        first.Scheme == second.Scheme && first.Host == second.Host &&
        first.Port == second.Port;

    private static bool TrustedAuthorizationOrigin(Uri address, Uri broker) =>
        SameOrigin(address, broker) ||
        (address.Scheme == Uri.UriSchemeHttps && address.Host == "accounts.google.com");

    private static string? KnownErrorCode(string? value) =>
        value is null ? null : GoogleErrorCodes.FirstOrDefault(code =>
            value.Contains(code, StringComparison.OrdinalIgnoreCase));

    // Return only an allowlisted category. URLs, authError payloads and
    // broker messages may contain identifying data and must never be logged.
    internal static string? ReadAuthorizationErrorCode(Uri address, Uri broker)
    {
        if (!TrustedAuthorizationOrigin(address, broker)) return null;
        string? error = Query(address, "error");
        if (!string.IsNullOrWhiteSpace(error))
            return KnownErrorCode(error) ?? "authorization_error";
        string? encoded = Query(address, "authError");
        if (!string.IsNullOrWhiteSpace(encoded) && encoded.Length <= 16384)
        {
            try
            {
                string padded = encoded.Replace('-', '+').Replace('_', '/');
                padded = padded.PadRight((padded.Length + 3) / 4 * 4, '=');
                string? known = KnownErrorCode(Encoding.UTF8.GetString(
                    Convert.FromBase64String(padded)));
                if (known is not null) return known;
            }
            catch (FormatException) { }
        }
        if (SameOrigin(address, broker) && address.AbsolutePath == "/" &&
            address.Fragment.Length is > 1 and <= 16384)
        {
            try
            {
                using var data = JsonDocument.Parse(Convert.FromBase64String(
                    address.Fragment.TrimStart('#')));
                if (data.RootElement.TryGetProperty("message_err", out var message) &&
                    message.ValueKind == JsonValueKind.String &&
                    !string.IsNullOrWhiteSpace(message.GetString()))
                    return KnownErrorCode(message.GetString()) ?? "authorization_error";
            }
            catch (Exception ex) when (ex is FormatException or JsonException) { }
        }
        return address.AbsolutePath.Contains("oauth", StringComparison.OrdinalIgnoreCase) &&
            address.AbsolutePath.EndsWith("/error", StringComparison.OrdinalIgnoreCase)
                ? "authorization_error" : null;
    }

    // Anonymous availability check only: its new cookie jar cannot reuse a
    // user's Google session. It never grants access, stores tokens, or hands
    // its cookies/state to Chrome. A 200 from /requests alone is insufficient.
    internal static async Task CheckPublicProviderAsync(CancellationToken cancel = default,
        Uri? brokerRoot = null)
    {
        Uri broker = brokerRoot ?? PublicBroker;
        if (!(broker == PublicBroker ||
            (broker.IsLoopback && broker.Scheme == Uri.UriSchemeHttp)))
            throw new ArgumentException("Unapproved Google broker origin");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        using var handler = new HttpClientHandler
        {
            CookieContainer = new CookieContainer(), UseCookies = true,
            AllowAutoRedirect = true, MaxAutomaticRedirections = 8,
            UseDefaultCredentials = false
        };
        using var http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        try
        {
            using var request = await http.GetAsync(new Uri(broker,
                "googleui/requests?server_use=true&driver_txt=googleui_go"),
                HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            if (!request.IsSuccessStatusCode)
                throw new AuthorizationException("provider_unavailable");
            await request.Content.LoadIntoBufferAsync(64 * 1024, deadline.Token)
                .ConfigureAwait(false);
            Uri google = ValidateBrokerAuthorization(
                await request.Content.ReadAsStringAsync(deadline.Token).ConfigureAwait(false), broker);
            using var response = await http.GetAsync(google,
                HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            Uri? arrived = response.RequestMessage?.RequestUri;
            if (arrived is null || !TrustedAuthorizationOrigin(arrived, broker))
                throw new AuthorizationException("provider_unavailable");
            string? error = ReadAuthorizationErrorCode(arrived, broker);
            if (error is not null && error != "authorization_error")
                throw new AuthorizationException(error);
            if (error is not null || (int)response.StatusCode >= 400)
            {
                await response.Content.LoadIntoBufferAsync(256 * 1024, deadline.Token)
                    .ConfigureAwait(false);
                string body = await response.Content.ReadAsStringAsync(deadline.Token)
                    .ConfigureAwait(false);
                throw new AuthorizationException(KnownErrorCode(body) ?? error ??
                    ((int)response.StatusCode >= 500 ? "provider_unavailable" : "authorization_error"));
            }
            if (!response.IsSuccessStatusCode)
                throw new AuthorizationException("provider_unavailable");
        }
        catch (AuthorizationException) { throw; }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        { throw new AuthorizationException("provider_unavailable"); }
        catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException)
        { throw new AuthorizationException("provider_unavailable"); }
    }

    internal static IReadOnlyList<string> ChromeArguments(string profile, int port, Uri broker)
    {
        var arguments = new List<string>
        {
            "--user-data-dir=" + profile,
            "--remote-debugging-address=127.0.0.1",
            "--remote-debugging-port=" + port,
            "--no-first-run", "--no-default-browser-check",
            "--disable-extensions", "--new-window"
        };
        if (broker.IsLoopback) arguments.Add("--headless=new");
        arguments.Add(new Uri(broker,
            "googleui/requests?server_use=true&driver_txt=googleui_go").AbsoluteUri);
        return arguments;
    }

    private static string? Query(Uri uri, string name)
    {
        foreach (string fragment in uri.Query.TrimStart('?').Split('&'))
        {
            var pieces = fragment.Split('=', 2);
            if (pieces.Length == 2 && pieces[0] == name)
                return Uri.UnescapeDataString(pieces[1].Replace('+', ' '));
        }
        return null;
    }

    internal static Uri ValidateBrokerAuthorization(string responseJson, Uri broker)
    {
        using var doc = JsonDocument.Parse(responseJson);
        string? destination = doc.RootElement.GetProperty("text").GetString();
        if (!Uri.TryCreate(destination, UriKind.Absolute, out var result))
            throw new IOException("OpenList returned an invalid Google authorization URL");
        bool fake = broker.IsLoopback;
        bool allowed = fake
            ? result.IsLoopback && result.Scheme == Uri.UriSchemeHttp
            : result.Host == "accounts.google.com" && result.Scheme == Uri.UriSchemeHttps;
        if (!allowed || Query(result, "scope") !=
            "https://www.googleapis.com/auth/drive" ||
            Query(result, "response_type") is not (null or "code") ||
            string.IsNullOrWhiteSpace(Query(result, "client_id")) ||
            (Query(result, "state")?.Length ?? 0) < 16 ||
            Query(result, "redirect_uri") !=
                new Uri(broker, "googleui/callback").AbsoluteUri)
            throw new IOException("OpenList Google authorization request failed strict origin/scope validation");
        return result;
    }

    internal static string? ReadApprovedCallback(Uri address, Uri broker)
    {
        if (address.Scheme != broker.Scheme ||
            address.Host != broker.Host || address.Port != broker.Port ||
            address.AbsolutePath != "/" || address.Fragment.Length < 10 ||
            address.Fragment.Length > 16384)
            return null;
        try
        {
            byte[] bytes = Convert.FromBase64String(address.Fragment.TrimStart('#'));
            if (bytes.Length > 12000)
                throw new IOException("OpenList callback data exceeded safety bound");
            using var doc = JsonDocument.Parse(bytes);
            var root = doc.RootElement;
            if (root.GetProperty("server_use").GetString() != "true" ||
                root.GetProperty("driver_txt").GetString() != "googleui_go" ||
                string.IsNullOrWhiteSpace(root.GetProperty("access_token").GetString()) ||
                string.IsNullOrWhiteSpace(root.GetProperty("refresh_token").GetString()))
                throw new IOException("OpenList callback did not contain a valid Google Drive grant");
            return JsonSerializer.Serialize(new
            {
                access_token = root.GetProperty("access_token").GetString(),
                refresh_token = root.GetProperty("refresh_token").GetString(),
                expires_in = 3600
            });
        }
        catch (Exception ex) when (ex is FormatException or JsonException or
            KeyNotFoundException or InvalidOperationException)
        {
            throw new IOException("OpenList Google callback was malformed", ex);
        }
    }

    private sealed class Cdp : IAsyncDisposable
    {
        private readonly ClientWebSocket _socket = new();
        private int _next;
        internal async Task ConnectAsync(string url, CancellationToken cancel) =>
            await _socket.ConnectAsync(new Uri(url), cancel).ConfigureAwait(false);

        internal async Task<int> CommandAsync(string method, object? parameters,
            CancellationToken cancel)
        {
            int id = Interlocked.Increment(ref _next);
            byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, object?>
            {
                ["id"] = id, ["method"] = method,
                ["params"] = parameters ?? new { }
            });
            await _socket.SendAsync(bytes.AsMemory(), WebSocketMessageType.Text,
                true, cancel).ConfigureAwait(false);
            return id;
        }

        internal async Task<JsonDocument> ReceiveAsync(CancellationToken cancel)
        {
            using var payload = new MemoryStream();
            byte[] bytes = new byte[16384];
            ValueWebSocketReceiveResult result;
            do
            {
                result = await _socket.ReceiveAsync(bytes.AsMemory(), cancel)
                    .ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                    throw new IOException("Chrome authorization browser closed");
                if (payload.Length + result.Count > MaxMessageBytes)
                    throw new IOException("Chrome authorization browser event exceeded limit");
                payload.Write(bytes, 0, result.Count);
            } while (!result.EndOfMessage);
            return JsonDocument.Parse(payload.ToArray());
        }

        internal async Task<JsonDocument> ReplyAsync(int id, CancellationToken cancel)
        {
            while (true)
            {
                var message = await ReceiveAsync(cancel).ConfigureAwait(false);
                if (message.RootElement.TryGetProperty("id", out var returned) &&
                    returned.GetInt32() == id)
                    return message;
                message.Dispose();
            }
        }

        public async ValueTask DisposeAsync()
        {
            try
            {
                using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(1));
                if (_socket.State == WebSocketState.Open)
                    await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure,
                        "done", deadline.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is WebSocketException or
                OperationCanceledException or ObjectDisposedException) { }
            _socket.Dispose();
        }
    }

    private static string ChromeExecutable()
    {
        var candidates = new[]
        {
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
                "Google", "Chrome", "Application", "chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                "Google", "Chrome", "Application", "chrome.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Google", "Chrome", "Application", "chrome.exe")
        };
        return candidates.FirstOrDefault(File.Exists) ??
            throw new FileNotFoundException("Google Chrome is required for browser-only login");
    }

    private static async Task<string> TabSocketAsync(HttpClient http, int port,
        CancellationToken cancel)
    {
        for (int i = 0; i < 80; i++)
        {
            cancel.ThrowIfCancellationRequested();
            try
            {
                using var response = await http.GetAsync(
                    "http://127.0.0.1:" + port + "/json/list", cancel)
                    .ConfigureAwait(false);
                if (response.IsSuccessStatusCode)
                {
                    using var doc = await JsonDocument.ParseAsync(
                        await response.Content.ReadAsStreamAsync(cancel),
                        cancellationToken: cancel).ConfigureAwait(false);
                    foreach (var item in doc.RootElement.EnumerateArray())
                        if (item.GetProperty("type").GetString() == "page" &&
                            item.TryGetProperty("webSocketDebuggerUrl", out var socket))
                            return socket.GetString()!;
                }
            }
            catch (Exception ex) when (ex is HttpRequestException or IOException or JsonException)
            { }
            await Task.Delay(125, cancel).ConfigureAwait(false);
        }
        throw new IOException("Chrome's isolated authorization tab did not start");
    }

    private static async Task<string> PollApprovedUrlAsync(HttpClient http,
        int port, Uri broker, CancellationToken cancel)
    {
        // Chromium may elide the fragment from Page.frameNavigated during
        // an OAuth callback redirect to the SPA's current document. The
        // isolated browser tab's authoritative URL keeps that hash.
        while (true)
        {
            cancel.ThrowIfCancellationRequested();
            try
            {
                using var reply = await http.GetAsync(
                    "http://127.0.0.1:" + port + "/json/list", cancel)
                    .ConfigureAwait(false);
                if (reply.IsSuccessStatusCode)
                {
                    using var doc = await JsonDocument.ParseAsync(
                        await reply.Content.ReadAsStreamAsync(cancel),
                        cancellationToken: cancel).ConfigureAwait(false);
                    foreach (var target in doc.RootElement.EnumerateArray())
                    {
                        if (target.GetProperty("type").GetString() != "page" ||
                            !target.TryGetProperty("url", out var url) ||
                            !Uri.TryCreate(url.GetString(), UriKind.Absolute, out var candidate))
                            continue;
                        string? error = ReadAuthorizationErrorCode(candidate, broker);
                        if (error is not null) throw new AuthorizationException(error);
                        var captured = ReadApprovedCallback(candidate, broker);
                        if (captured is not null) return captured;
                    }
                }
            }
            catch (AuthorizationException) { throw; }
            catch (Exception ex) when (ex is HttpRequestException or
                IOException or JsonException) { }
            await Task.Delay(80, cancel).ConfigureAwait(false);
        }
    }

    private static async Task<Uri> ReadLoginPageAsync(Cdp cdp, Uri broker,
        CancellationToken cancel)
    {
        for (int attempt = 0; attempt < 45; attempt++)
        {
            int id = await cdp.CommandAsync("Runtime.evaluate", new
            {
                expression = "document.body ? document.body.innerText : ''",
                returnByValue = true
            }, cancel).ConfigureAwait(false);
            using var reply = await cdp.ReplyAsync(id, cancel).ConfigureAwait(false);
            try
            {
                string? page = reply.RootElement.GetProperty("result")
                    .GetProperty("result").GetProperty("value").GetString();
                if (!string.IsNullOrWhiteSpace(page) && page.Contains("\"text\""))
                    return ValidateBrokerAuthorization(page, broker);
            }
            catch (Exception ex) when (ex is KeyNotFoundException or
                InvalidOperationException or JsonException)
            { }
            await Task.Delay(170, cancel).ConfigureAwait(false);
        }
        throw new IOException("OpenList did not provide the Google browser sign-in URL");
    }

    private static bool IsCallback(Uri candidate, Uri broker, string state)
    {
        return candidate.Scheme == broker.Scheme &&
            candidate.Host == broker.Host && candidate.Port == broker.Port &&
            candidate.AbsolutePath == "/googleui/callback" &&
            Query(candidate, "state") == state;
    }

    private static async Task<string?> ReadBrowserErrorCodeAsync(Cdp cdp,
        CancellationToken cancel)
    {
        // Called only for a trusted OAuth error document. JavaScript returns
        // an allowlisted code, never page text, account names or credentials.
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromSeconds(2));
        string expression = "(() => { const text = (document.body?.innerText || '').toLowerCase(); " +
            "return " + JsonSerializer.Serialize(GoogleErrorCodes) +
            ".find(code => text.includes(code)) || ''; })()";
        try
        {
            for (int attempt = 0; attempt < 6; attempt++)
            {
                int id = await cdp.CommandAsync("Runtime.evaluate", new
                    { expression, returnByValue = true }, deadline.Token).ConfigureAwait(false);
                using var reply = await cdp.ReplyAsync(id, deadline.Token).ConfigureAwait(false);
                if (reply.RootElement.TryGetProperty("result", out var result) &&
                    result.TryGetProperty("result", out var value) &&
                    value.TryGetProperty("value", out var code) &&
                    code.ValueKind == JsonValueKind.String &&
                    KnownErrorCode(code.GetString()) is string known)
                    return known;
                await Task.Delay(120, deadline.Token).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested) { }
        catch (Exception ex) when (ex is WebSocketException or IOException or JsonException) { }
        return null;
    }

    internal static async Task<string> AuthorizeAsync(Action<string> progress,
        CancellationToken cancel, Uri? brokerRoot = null)
    {
        Uri broker = brokerRoot ?? PublicBroker;
        if (!(broker == PublicBroker ||
            (broker.IsLoopback && broker.Scheme == Uri.UriSchemeHttp)))
            throw new ArgumentException("Unapproved Google broker origin");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromMinutes(4));
        if (!broker.IsLoopback)
        {
            progress("Google 로그인 서버 상태를 확인하는 중...");
            await CheckPublicProviderAsync(deadline.Token).ConfigureAwait(false);
        }
        using var portReserve = new TcpListener(IPAddress.Loopback, 0);
        portReserve.Start();
        int port = ((IPEndPoint)portReserve.LocalEndpoint).Port;
        portReserve.Stop();
        string profile = Path.Combine(Path.GetTempPath(),
            "FileHub-OpenList-Chrome-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(profile);
        Process? chrome = null;
        try
        {
            progress("Chrome에서 Google 계정 로그인 창을 여는 중...");
            var start = new ProcessStartInfo(ChromeExecutable())
            {
                UseShellExecute = false, CreateNoWindow = false
            };
            foreach (string arg in ChromeArguments(profile, port, broker))
                start.ArgumentList.Add(arg);
            chrome = Process.Start(start) ??
                throw new IOException("Chrome Google authorization process did not start");
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(3) };
            string socket = await TabSocketAsync(http, port, deadline.Token)
                .ConfigureAwait(false);
            await using var cdp = new Cdp();
            await cdp.ConnectAsync(socket, deadline.Token).ConfigureAwait(false);
            Uri google = await ReadLoginPageAsync(cdp, broker, deadline.Token)
                .ConfigureAwait(false);
            if (broker.IsLoopback)
                Console.Error.WriteLine("OPENLIST_CHROME_LAB_LOGIN_URL_READY");
            string state = Query(google, "state")!;
            using (var response = await cdp.ReplyAsync(
                await cdp.CommandAsync("Page.enable", null, deadline.Token),
                deadline.Token)) { }
            using (var response = await cdp.ReplyAsync(
                await cdp.CommandAsync("Network.enable", null, deadline.Token),
                deadline.Token)) { }
            if (!broker.IsLoopback)
            {
                // The broker's SPA normally clears the URL hash as soon as
                // it displays token fields. Keep the one-time fragment
                // available until FileHub captures it; do not load its JS.
                using var ignore = await cdp.ReplyAsync(
                    await cdp.CommandAsync("Network.setBlockedURLs", new
                    {
                        urls = new[] { "*api.oplist.org/assets/*" }
                    }, deadline.Token), deadline.Token);
            }
            await cdp.CommandAsync("Page.navigate", new
                { url = google.AbsoluteUri }, deadline.Token);
            progress("Google 승인 화면에서 계정을 선택하고 허용해 주세요.");
            bool stateVerified = false;
            string? fragment = null;
            using var browserTasks = CancellationTokenSource.CreateLinkedTokenSource(deadline.Token);
            Task<string> tabHashPoll = PollApprovedUrlAsync(
                http, port, broker, browserTasks.Token);
            Task<JsonDocument>? pendingReceive = null;
            try
            {
            while (true)
            {
                JsonDocument nextMessage;
                try
                {
                    var nextEvent = pendingReceive = cdp.ReceiveAsync(browserTasks.Token);
                    if (fragment is null)
                    {
                        // Rejections happen before an approved callback.
                        // Observe them immediately, even without stateVerified.
                        var first = await Task.WhenAny(nextEvent, tabHashPoll)
                            .ConfigureAwait(false);
                        if (first == tabHashPoll)
                        {
                            fragment = await tabHashPoll.ConfigureAwait(false);
                            if (stateVerified) return fragment;
                        }
                    }
                    nextMessage = await nextEvent.ConfigureAwait(false);
                    pendingReceive = null;
                }
                catch (AuthorizationException) { throw; }
                catch (Exception ex) when (ex is WebSocketException or IOException)
                {
                    // Let an already-visible Google error win over a tab
                    // closing its WebSocket. Never mislabel it as success.
                    await Task.WhenAny(tabHashPoll, Task.Delay(250, deadline.Token))
                        .ConfigureAwait(false);
                    if (tabHashPoll.IsCompleted)
                    {
                        fragment = await tabHashPoll.ConfigureAwait(false);
                        if (stateVerified) return fragment;
                    }
                    throw new AuthorizationException("browser_connection_lost");
                }
                using var message = nextMessage;
                var root = message.RootElement;
                if (!root.TryGetProperty("method", out var method)) continue;
                string? kind = method.GetString();
                if (!root.TryGetProperty("params", out var args)) continue;
                if (kind == "Network.responseReceived" &&
                    args.TryGetProperty("type", out var responseType) &&
                    responseType.GetString() == "Document" &&
                    args.TryGetProperty("response", out var response) &&
                    response.TryGetProperty("status", out var status) &&
                    status.TryGetDouble(out double httpStatus) && httpStatus >= 400 &&
                    response.TryGetProperty("url", out var responseUrl) &&
                    Uri.TryCreate(responseUrl.GetString(), UriKind.Absolute, out var failedUrl) &&
                    TrustedAuthorizationOrigin(failedUrl, broker))
                {
                    string? code = ReadAuthorizationErrorCode(failedUrl, broker);
                    if (code is null or "authorization_error")
                        code = await ReadBrowserErrorCodeAsync(cdp, deadline.Token)
                            .ConfigureAwait(false) ?? code;
                    throw new AuthorizationException(code ??
                        (httpStatus >= 500 ? "provider_unavailable" : "authorization_error"));
                }
                if (kind == "Network.requestWillBeSent" &&
                    args.TryGetProperty("request", out var request) &&
                    request.TryGetProperty("url", out var requested) &&
                    Uri.TryCreate(requested.GetString(), UriKind.Absolute, out var callback) &&
                    callback.Scheme == broker.Scheme && callback.Host == broker.Host &&
                    callback.AbsolutePath == "/googleui/callback")
                {
                    if (!IsCallback(callback, broker, state))
                        throw new IOException("Google browser consent state did not match");
                    string? error = ReadAuthorizationErrorCode(callback, broker);
                    if (error is not null) throw new AuthorizationException(error);
                    stateVerified = true;
                    if (broker.IsLoopback)
                        Console.Error.WriteLine("OPENLIST_CHROME_LAB_CALLBACK_STATE_MATCHED");
                }
                string? rawUrl = null;
                if (kind == "Page.navigatedWithinDocument" &&
                    args.TryGetProperty("url", out var current))
                    rawUrl = current.GetString();
                else if (kind == "Page.frameNavigated" &&
                    args.TryGetProperty("frame", out var frame) &&
                    frame.TryGetProperty("url", out var navigated))
                    rawUrl = navigated.GetString();
                if (rawUrl is not null && Uri.TryCreate(rawUrl,
                    UriKind.Absolute, out var visible))
                {
                    string? error = ReadAuthorizationErrorCode(visible, broker);
                    if (error is not null) throw new AuthorizationException(error);
                    string? found = ReadApprovedCallback(visible, broker);
                    if (found is not null)
                    {
                        fragment ??= found;
                        if (broker.IsLoopback)
                            Console.Error.WriteLine("OPENLIST_CHROME_LAB_FRAGMENT_CAPTURED");
                    }
                }
                if (fragment is not null && stateVerified)
                    return fragment;
                if (stateVerified && tabHashPoll.IsCompleted)
                    return await tabHashPoll.ConfigureAwait(false);
            }
            }
            finally
            {
                // Finish every browser task while both transports are still
                // alive. In particular, polling can win before a pending
                // Receive sees the final Google error or callback event.
                browserTasks.Cancel();
                if (pendingReceive is not null)
                {
                    try { using var unused = await pendingReceive.ConfigureAwait(false); }
                    catch (Exception) { /* Observe canceled/failed background receive. */ }
                }
                try { _ = await tabHashPoll.ConfigureAwait(false); }
                catch (Exception) { /* Preserve the primary outcome; observe poll failure. */ }
            }
        }
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new AuthorizationException("authorization_timeout");
        }
        catch (WebSocketException)
        { throw new AuthorizationException("browser_connection_lost"); }
        finally
        {
            deadline.Cancel();
            if (chrome is not null)
            {
                try
                {
                    if (!chrome.HasExited)
                    {
                        chrome.Kill(entireProcessTree: true);
                        await chrome.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3))
                            .ConfigureAwait(false);
                    }
                }
                catch (Exception ex) when (ex is InvalidOperationException or
                    System.ComponentModel.Win32Exception or TimeoutException) { }
                chrome.Dispose();
            }
            try { Directory.Delete(profile, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
