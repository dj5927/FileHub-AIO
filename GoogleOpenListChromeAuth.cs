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
                if (_socket.State == WebSocketState.Open)
                    await _socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure,
                        "done", CancellationToken.None).ConfigureAwait(false);
            }
            catch (WebSocketException) { }
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
                        var captured = ReadApprovedCallback(candidate, broker);
                        if (captured is not null) return captured;
                    }
                }
            }
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

    internal static async Task<string> AuthorizeAsync(Action<string> progress,
        CancellationToken cancel, Uri? brokerRoot = null)
    {
        Uri broker = brokerRoot ?? PublicBroker;
        if (!(broker == PublicBroker ||
            (broker.IsLoopback && broker.Scheme == Uri.UriSchemeHttp)))
            throw new ArgumentException("Unapproved Google broker origin");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancel);
        deadline.CancelAfter(TimeSpan.FromMinutes(4));
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
            foreach (string arg in new[]
            {
                "--user-data-dir=" + profile,
                "--remote-debugging-address=127.0.0.1",
                "--remote-debugging-port=" + port,
                "--no-first-run", "--no-default-browser-check",
                "--disable-extensions", "--incognito",
                new Uri(broker,
                    "googleui/requests?server_use=true&driver_txt=googleui_go").AbsoluteUri
            }) start.ArgumentList.Add(arg);
            if (broker.IsLoopback)
            {
                // Isolated CI test may run without an interactive desktop.
                start.ArgumentList.Insert(start.ArgumentList.Count - 1,
                    "--headless=new");
            }
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
            Task<string> tabHashPoll = PollApprovedUrlAsync(
                http, port, broker, deadline.Token);
            while (true)
            {
                var nextEvent = cdp.ReceiveAsync(deadline.Token);
                if (stateVerified && fragment is null)
                {
                    var first = await Task.WhenAny(nextEvent, tabHashPoll)
                        .ConfigureAwait(false);
                    if (first == tabHashPoll)
                        return await tabHashPoll.ConfigureAwait(false);
                }
                using var message = await nextEvent.ConfigureAwait(false);
                var root = message.RootElement;
                if (!root.TryGetProperty("method", out var method)) continue;
                string? kind = method.GetString();
                if (!root.TryGetProperty("params", out var args)) continue;
                if (kind == "Network.requestWillBeSent" &&
                    args.TryGetProperty("request", out var request) &&
                    request.TryGetProperty("url", out var requested) &&
                    Uri.TryCreate(requested.GetString(), UriKind.Absolute, out var callback) &&
                    callback.Scheme == broker.Scheme && callback.Host == broker.Host &&
                    callback.AbsolutePath == "/googleui/callback")
                {
                    if (!IsCallback(callback, broker, state))
                        throw new IOException("Google browser consent state did not match");
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
        catch (OperationCanceledException) when (!cancel.IsCancellationRequested)
        {
            throw new IOException("Google browser approval timed out");
        }
        finally
        {
            if (chrome is not null)
            {
                try
                {
                    if (!chrome.HasExited) chrome.Kill(entireProcessTree: true);
                }
                catch (InvalidOperationException) { }
                chrome.Dispose();
            }
            try { Directory.Delete(profile, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
