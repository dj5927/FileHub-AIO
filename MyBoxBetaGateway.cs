using System.Globalization;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;

namespace NASMount;

/// <summary>
/// MYBOX Open API bridge. Authenticated upload and folder operations are
/// available only when the user explicitly selects beta write mode.
/// PAT remains in Credential Manager and is never passed to rclone.
/// </summary>
internal sealed partial class MyBoxBetaGateway : IDisposable
{
    internal sealed record Entry(string Name, string Id, bool IsFolder,
        long Size, DateTimeOffset Modified);

    internal sealed partial class Api : IDisposable
    {
        private readonly HttpClient http;
        private readonly HttpClient downloadClient =
            new(new HttpClientHandler { AllowAutoRedirect = false })
            { Timeout = TimeSpan.FromMinutes(5) };
        private readonly SemaphoreSlim gate = new(1, 1);
        private readonly Dictionary<string, (DateTime Timestamp, List<Entry> Entries)> cache = [];
        internal Api(string token, HttpMessageHandler? handler = null,
            HttpMessageHandler? uploadHandler = null)
        {
            if (!ValidToken(token)) throw new ArgumentException("MYBOX 개인 액세스 토큰(PAT)이 올바르지 않습니다.");
            http = handler is null ? new HttpClient() : new HttpClient(handler);
            http.BaseAddress = new Uri("https://open-api.mybox.naver.com/v1/");
            http.Timeout = TimeSpan.FromSeconds(35);
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
            uploadClient = uploadHandler is null ?
                new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) :
                new HttpClient(uploadHandler);
            uploadClient.Timeout = TimeSpan.FromMinutes(45);
        }
        internal static bool ValidToken(string? token) =>
            token is { Length: >= 20 and <= 1024 } &&
            token.StartsWith("mbx_pat_", StringComparison.Ordinal) &&
            token.All(c => c is >= '!' and <= '~');

        internal static (List<Entry> Items, string? Next) ParseList(string json)
        {
            using var data = JsonDocument.Parse(json);
            var root = data.RootElement;
            var items = new List<Entry>();
            foreach (var item in root.GetProperty("resources").EnumerateArray())
            {
                string name = item.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                string id = item.TryGetProperty("resourceId", out var r) ? r.GetString() ?? "" : "";
                string type = item.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
                if (name.Length == 0 || name is "." or ".." ||
                    name.IndexOfAny(['/', '\\', '\r', '\n']) >= 0 ||
                    id.Length == 0 || type is not ("folder" or "file")) continue;
                long size = item.TryGetProperty("size", out var s) &&
                    s.TryGetInt64(out long length) ? Math.Max(0, length) : 0;
                string? stamp = item.TryGetProperty("modifiedAt", out var m) ? m.GetString() : null;
                var modified = DateTimeOffset.TryParse(stamp, CultureInfo.InvariantCulture,
                    DateTimeStyles.None, out var parsed) ? parsed : DateTimeOffset.UnixEpoch;
                items.Add(new Entry(name, id, type == "folder", size, modified));
            }
            string? next = null;
            if (root.TryGetProperty("responseMetaData", out var meta) &&
                meta.ValueKind == JsonValueKind.Object &&
                meta.TryGetProperty("nextCursor", out var cursor) &&
                cursor.ValueKind == JsonValueKind.String)
                next = cursor.GetString();
            return (items, string.IsNullOrWhiteSpace(next) ? null : next);
        }

        internal async Task<List<Entry>> ListAsync(string? folderId, CancellationToken cancel)
        {
            await gate.WaitAsync(cancel);
            try
            {
                string key = folderId ?? "";
                if (cache.TryGetValue(key, out var found) &&
                    DateTime.UtcNow - found.Timestamp < TimeSpan.FromSeconds(15))
                    return found.Entries;
                var result = new List<Entry>();
                string? cursor = null;
                var seen = new HashSet<string>(StringComparer.Ordinal);
                for (int page = 0; page < 100; page++)
                {
                    string endpoint = folderId is null
                        ? "drive/resources?count=1000"
                        : "drive/folders/" + Uri.EscapeDataString(folderId) +
                            "/resources?count=1000";
                    if (cursor is not null)
                        endpoint += "&cursor=" + Uri.EscapeDataString(cursor);
                    using var response = await http.GetAsync(endpoint, cancel);
                    if (!response.IsSuccessStatusCode)
                        throw new IOException("MYBOX 목록 조회 실패 (HTTP " +
                            (int)response.StatusCode + "). " +
                            (response.StatusCode == HttpStatusCode.Unauthorized ?
                                "토큰을 재발급하세요." :
                                (int)response.StatusCode == 429 ?
                                "요청 한도를 초과했습니다." : "서비스 상태를 확인하세요."));
                    var parsed = ParseList(await response.Content.ReadAsStringAsync(cancel));
                    result.AddRange(parsed.Items);
                    cursor = parsed.Next;
                    if (cursor is null) break;
                    if (!seen.Add(cursor)) throw new IOException("MYBOX API 목록 커서가 반복됩니다.");
                    if (page == 99) throw new IOException("MYBOX 폴더 목록 페이지 수 한도를 초과했습니다.");
                }
                cache[key] = (DateTime.UtcNow, result);
                return result;
            }
            finally { gate.Release(); }
        }

        internal async Task<Entry?> ResolveAsync(string[] segments, CancellationToken cancel)
        {
            Entry? entry = null;
            string? parent = null;
            for (int i = 0; i < segments.Length; i++)
            {
                var children = await ListAsync(parent, cancel);
                entry = children.FirstOrDefault(x => x.Name.Equals(
                    segments[i], StringComparison.OrdinalIgnoreCase));
                if (entry is null || i < segments.Length - 1 && !entry.IsFolder)
                    return null;
                parent = entry.Id;
            }
            return entry;
        }

        private static bool AllowedDownloadUrl(Uri uri) =>
            uri.Scheme == Uri.UriSchemeHttps && !uri.IsLoopback &&
            !IPAddress.TryParse(uri.Host, out _) &&
            !uri.Host.EndsWith(".local", StringComparison.OrdinalIgnoreCase) &&
            !uri.Host.EndsWith(".internal", StringComparison.OrdinalIgnoreCase);

        internal async Task<HttpResponseMessage> DownloadAsync(
            Entry item, string? range, CancellationToken cancel)
        {
            using var urlResponse = await http.GetAsync(
                "drive/files/" + Uri.EscapeDataString(item.Id) + "/download", cancel);
            if (!urlResponse.IsSuccessStatusCode)
                throw new IOException("MYBOX 다운로드 URL 발급 실패 (HTTP " +
                    (int)urlResponse.StatusCode + ")");
            using var json = JsonDocument.Parse(
                await urlResponse.Content.ReadAsStringAsync(cancel));
            var raw = json.RootElement.GetProperty("downloadUrl").GetString();
            if (!Uri.TryCreate(raw, UriKind.Absolute, out var url) ||
                !AllowedDownloadUrl(url))
                throw new IOException("안전하지 않은 MYBOX 다운로드 URL이 반환됐습니다.");
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            if (!string.IsNullOrWhiteSpace(range))
                request.Headers.TryAddWithoutValidation("Range", range);
            // Retain the downloader for the whole gateway lifetime: disposing
            // an HttpClient before the streaming body is consumed aborts GET.
            return await downloadClient.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, cancel);
        }
        public void Dispose() { http.Dispose(); downloadClient.Dispose(); uploadClient.Dispose(); gate.Dispose(); }
    }

    private readonly Api api;
    private readonly bool writable;
    private readonly TcpListener tcp;
    private readonly CancellationTokenSource stopped = new();
    private readonly string nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
    private readonly List<Task> requests = [];
    private Task? acceptLoop;
    private bool disposed;
    internal string Url { get; }

    private MyBoxBetaGateway(Api client, bool writable = false)
    {
        api = client;
        this.writable = writable;
        tcp = new TcpListener(IPAddress.Loopback, 0);
        tcp.Start();
        int port = ((IPEndPoint)tcp.LocalEndpoint).Port;
        Url = "http://127.0.0.1:" + port + "/" + nonce + "/";
    }

    internal static async Task<MyBoxBetaGateway> StartAsync(string pat, bool writable = false,
        CancellationToken cancel = default)
    {
        var client = new Api(pat);
        MyBoxBetaGateway? gateway = null;
        try
        {
            // Do not show a connected drive when PAT is rejected by MYBOX.
            await client.ListAsync(null, cancel);
            gateway = new MyBoxBetaGateway(client, writable);
            gateway.acceptLoop = Task.Run(gateway.AcceptAsync);
            return gateway;
        }
        catch
        {
            gateway?.Dispose();
            client.Dispose();
            throw;
        }
    }

    private async Task AcceptAsync()
    {
        while (!stopped.IsCancellationRequested)
        {
            try
            {
                var socket = await tcp.AcceptTcpClientAsync(stopped.Token);
                lock (requests)
                    requests.Add(Task.Run(() => ServeAsync(socket)));
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (stopped.IsCancellationRequested) { break; }
        }
    }

    private static byte[] Utf8(string s) => Encoding.UTF8.GetBytes(s);
    private static async Task ReplyAsync(NetworkStream stream, int code, string reason,
        byte[] data, string type, bool head = false,
        IDictionary<string, string>? more = null, CancellationToken cancel = default)
    {
        var header = new StringBuilder("HTTP/1.1 ").Append(code).Append(' ').Append(reason)
            .Append("\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n")
            .Append("Content-Type: ").Append(type).Append("\r\n")
            .Append("Content-Length: ")
            .Append(more is not null &&
                    more.TryGetValue("Content-Length", out var declared)
                ? declared : data.Length.ToString(CultureInfo.InvariantCulture))
            .Append("\r\n");
        if (more is not null)
            foreach (var pair in more)
                if (pair.Key != "Content-Length") header.Append(pair.Key).Append(": ")
                    .Append(pair.Value).Append("\r\n");
        header.Append("\r\n");
        await stream.WriteAsync(Utf8(header.ToString()), cancel);
        if (!head && data.Length > 0) await stream.WriteAsync(data, cancel);
    }

    private static string[]? SplitPath(string path, string nonce)
    {
        var query = path.Split('?', 2)[0];
        string prefix = "/" + nonce;
        if (!(query == prefix || query.StartsWith(prefix + "/", StringComparison.Ordinal)))
            return null;
        string rest = query.Length == prefix.Length ? "" : query[(prefix.Length + 1)..];
        if (rest.Length == 0) return [];
        var parts = rest.Split('/', StringSplitOptions.RemoveEmptyEntries);
        var decoded = new List<string>();
        foreach (var part in parts)
        {
            string name = Uri.UnescapeDataString(part);
            if (name.Length == 0 || name is "." or ".." ||
                name.IndexOfAny(['/', '\\', '\r', '\n']) >= 0) return null;
            decoded.Add(name);
        }
        return decoded.ToArray();
    }
    private string Href(IEnumerable<string> parts, bool folder)
    {
        var suffix = string.Join("/", parts.Select(Uri.EscapeDataString));
        return "/" + nonce + "/" + suffix + (folder && suffix.Length > 0 ? "/" : "");
    }

    private static readonly XNamespace D = "DAV:";
    private XElement Element(string[] names, Entry? entry, bool folder)
    {
        string name = entry?.Name ?? "";
        string stamp = (entry?.Modified ?? DateTimeOffset.UnixEpoch)
            .UtcDateTime.ToString("R", CultureInfo.InvariantCulture);
        return new XElement(D + "response",
            new XElement(D + "href", Href(names, folder)),
            new XElement(D + "propstat",
                new XElement(D + "prop",
                    new XElement(D + "displayname", name),
                    new XElement(D + "resourcetype",
                        folder ? new XElement(D + "collection") : null),
                    new XElement(D + "getcontentlength", folder ? 0 : entry?.Size ?? 0),
                    new XElement(D + "getlastmodified", stamp),
                    new XElement(D + "creationdate",
                        (entry?.Modified ?? DateTimeOffset.UnixEpoch).UtcDateTime.ToString("O")),
                    new XElement(D + "getetag", "\"" + (entry?.Id ?? "root") + "\"")),
                new XElement(D + "status", "HTTP/1.1 200 OK")));
    }

    private async Task ServeAsync(TcpClient socket)
    {
        using (socket)
        using (var stream = socket.GetStream())
        using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(stopped.Token))
        {
            timeout.CancelAfter(TimeSpan.FromSeconds(90));
            var cancel = timeout.Token;
            try
            {
                // Read headers directly from the network stream. StreamReader
                // prefetches PUT body bytes, corrupting multipart uploads.
                string? first = await ReadHttpLineAsync(stream, cancel);
                if (first is null || first.Length > 4096) return;
                string[] command = first.Split(' ');
                if (command.Length < 3) return;
                string method = command[0].ToUpperInvariant();
                if (writable && method == "PUT")
                    timeout.CancelAfter(TimeSpan.FromMinutes(45));
                if (Environment.GetEnvironmentVariable("NASMOUNT_MYBOX_TRACE") == "1")
                    AppStore.Log("MYBOX_LOOPBACK_METHOD " + method);
                string? range = null, depth = null, destination = null, overwrite = null;
                long? bodyLength = null;
                bool chunked = false;
                int bytes = first.Length;
                while (true)
                {
                    string? line = await ReadHttpLineAsync(stream, cancel);
                    if (line is null) return;
                    bytes += line.Length;
                    if (bytes > 16 * 1024) return;
                    if (line.Length == 0) break;
                    int colon = line.IndexOf(':');
                    if (colon <= 0) continue;
                    string field = line[..colon].Trim();
                    string value = line[(colon + 1)..].Trim();
                    if (field.Equals("Range", StringComparison.OrdinalIgnoreCase)) range = value;
                    if (field.Equals("Depth", StringComparison.OrdinalIgnoreCase)) depth = value;
                    if (field.Equals("Destination", StringComparison.OrdinalIgnoreCase)) destination = value;
                    if (field.Equals("Overwrite", StringComparison.OrdinalIgnoreCase)) overwrite = value;
                    if (field.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase) &&
                        value.Contains("chunked", StringComparison.OrdinalIgnoreCase)) chunked = true;
                    if (field.Equals("Content-Length", StringComparison.OrdinalIgnoreCase) &&
                        long.TryParse(value, out long declaredSize) && declaredSize >= 0) bodyLength = declaredSize;
                }
                var pieces = SplitPath(command[1], nonce);
                if (pieces is null)
                {
                    await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                    return;
                }
                // rclone PROPFIND carries XML. Unread request bytes on close
                // trigger a TCP reset on Windows and break metadata reads.
                if (method is "PROPFIND" or "OPTIONS" or "GET" or "HEAD")
                    await DrainRequestBodyAsync(stream, bodyLength, chunked, cancel);
                if (method == "OPTIONS")
                {
                    await ReplyAsync(stream, 200, "OK", [], "text/plain",
                        more: new Dictionary<string,string> {
                            ["Allow"]=writable ? "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, DELETE, MOVE, COPY" :
                                "OPTIONS, GET, HEAD, PROPFIND",
                            ["DAV"]="1", ["MS-Author-Via"]="DAV" }, cancel: cancel);
                    return;
                }
                if (method is "PUT" or "MKCOL" or "DELETE" or "MOVE" or "COPY")
                {
                    if (!writable)
                        await ReplyAsync(stream, 403, "Forbidden", [], "text/plain", cancel: cancel);
                    else
                        await HandleAuthoringAsync(stream, method, pieces, destination,
                            overwrite, bodyLength, chunked, cancel);
                    return;
                }
                if (method is not ("GET" or "HEAD" or "PROPFIND"))
                {
                    await ReplyAsync(stream, 405, "Method Not Allowed", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                Entry? entry = pieces.Length == 0
                    ? null : await api.ResolveAsync(pieces, cancel);
                if (pieces.Length > 0 && entry is null)
                {
                    await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                    return;
                }
                bool directory = pieces.Length == 0 || entry!.IsFolder;
                if (method == "PROPFIND")
                {
                    if (depth is not null && depth is not ("0" or "1"))
                    {
                        await ReplyAsync(stream, 403, "Forbidden", [],
                            "text/plain", cancel: cancel);
                        return;
                    }
                    var response = new XElement(D + "multistatus", Element(pieces, entry, directory));
                    if (directory && depth != "0")
                    {
                        var children = await api.ListAsync(entry?.Id, cancel);
                        foreach (var child in children)
                            response.Add(Element([..pieces, child.Name], child, child.IsFolder));
                    }
                    byte[] xml = Utf8(new XDocument(new XDeclaration("1.0","utf-8",null),response)
                        .ToString(SaveOptions.DisableFormatting));
                    await ReplyAsync(stream, 207, "Multi-Status", xml,
                        "application/xml; charset=utf-8", cancel: cancel);
                    return;
                }
                if (directory)
                {
                    await ReplyAsync(stream, 403, "Forbidden", [], "text/plain", cancel: cancel);
                    return;
                }
                if (method == "HEAD")
                {
                    await ReplyAsync(stream, 200, "OK", new byte[0],
                        "application/octet-stream", head: true,
                        more: new Dictionary<string,string> {
                            ["Content-Length"] = entry!.Size.ToString(CultureInfo.InvariantCulture),
                            ["Accept-Ranges"] = "bytes"
                        }, cancel: cancel);
                    return;
                }
                using var download = await api.DownloadAsync(entry!, range, cancel);
                if (!download.IsSuccessStatusCode ||
                    range is not null && download.StatusCode != HttpStatusCode.PartialContent)
                {
                    await ReplyAsync(stream, 502, "Bad Gateway", [], "text/plain", cancel: cancel);
                    return;
                }
                long? length = download.Content.Headers.ContentLength;
                var hdr = new StringBuilder("HTTP/1.1 ")
                    .Append((int)download.StatusCode)
                    .Append(download.StatusCode == HttpStatusCode.PartialContent ?
                        " Partial Content\r\n" : " OK\r\n")
                    .Append("Connection: close\r\nContent-Type: application/octet-stream\r\n")
                    .Append("Accept-Ranges: bytes\r\n");
                if (length is not null)
                    hdr.Append("Content-Length: ").Append(length.Value).Append("\r\n");
                else
                    hdr.Append("Transfer-Encoding: chunked\r\n");
                if (download.Content.Headers.ContentRange is { } contentRange)
                    hdr.Append("Content-Range: ").Append(contentRange).Append("\r\n");
                hdr.Append("\r\n");
                await stream.WriteAsync(Utf8(hdr.ToString()), cancel);
                await using var payload = await download.Content.ReadAsStreamAsync(cancel);
                if (length is not null) await payload.CopyToAsync(stream, cancel);
                else
                {
                    var buffer = new byte[64 * 1024];
                    int size;
                    while ((size = await payload.ReadAsync(buffer, cancel)) > 0)
                    {
                        await stream.WriteAsync(Utf8(size.ToString("X") + "\r\n"), cancel);
                        await stream.WriteAsync(buffer.AsMemory(0, size), cancel);
                        await stream.WriteAsync(Utf8("\r\n"), cancel);
                    }
                    await stream.WriteAsync(Utf8("0\r\n\r\n"), cancel);
                }
            }
            catch (OperationCanceledException)
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_MYBOX_TRACE") == "1")
                    AppStore.Log("MYBOX_LOOPBACK_CANCELLED");
            }
            catch (IOException ex)
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_MYBOX_TRACE") == "1")
                    AppStore.Log("MYBOX_LOOPBACK_IO " + ex.GetType().Name +
                        " " + ex.Message.Replace("\r", "").Replace("\n", ""));
            }
            catch (Exception ex)
            {
                AppStore.Log("MYBOX_BETA_READONLY_GATEWAY_ERROR " +
                    ex.GetType().Name + " (token_and_paths_redacted)");
                try { await ReplyAsync(stream, 502, "Bad Gateway", [],
                    "text/plain", cancel: cancel); }
                catch { }
            }
        }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stopped.Cancel();
        tcp.Stop();
        api.Dispose();
        // A just-accepted socket may still read stopped.Token in ServeAsync.
        // Leave CTS for GC after cancellation rather than racing it here.
    }

    internal static void PolicyTest()
    {
        const string mock = """{"resources":[{"name":"사진","resourceId":"folder-1","type":"folder","size":0,"modifiedAt":"2026-08-01T00:00:00+09:00"},{"name":"test.txt","resourceId":"file-1","type":"file","size":42}],"responseMetaData":{"nextCursor":null}}""";
        var parsed = Api.ParseList(mock);
        if (parsed.Items.Count != 2 || parsed.Items[0].Name != "사진" ||
            !parsed.Items[0].IsFolder || parsed.Items[1].Size != 42 ||
            parsed.Next is not null || Api.ValidToken("invalid"))
            throw new InvalidOperationException("MYBOX API official root-list parsing failed");
        var sample = "mbx_pat_" + new string('z', 32);
        if (!Api.ValidToken(sample)) throw new InvalidOperationException("MYBOX PAT shape invalid");
        var client = new Api(sample);
        using var gateway = new MyBoxBetaGateway(client);
        string root = "/" + gateway.nonce + "/";
        if (SplitPath(root, gateway.nonce)?.Length != 0 ||
            SplitPath(root + "a%2Fb", gateway.nonce) is not null ||
            SplitPath("/not-owned/", gateway.nonce) is not null ||
            SplitPath(root + "%2e%2e/", gateway.nonce) is not null)
            throw new InvalidOperationException("MYBOX gateway path guard failed");
        string xml = new XDocument(new XElement(D + "multistatus",
            gateway.Element([], null, true))).ToString();
        if (!xml.Contains("multistatus") || !xml.Contains("collection") ||
            !xml.Contains(root))
            throw new InvalidOperationException("MYBOX DAV propfind formatting failed");
        Console.WriteLine("MYBOX_BETA_READONLY_GATEWAY_OFFLINE_POLICY_PASS");
    }

    private sealed class LabApiHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancel)
        {
            if (request.RequestUri?.Host != "open-api.mybox.naver.com" ||
                request.Method != HttpMethod.Get)
                throw new InvalidOperationException("Unexpected mock MYBOX request");
            const string sample = """{"resources":[{"name":"docs","resourceId":"folder-1","type":"folder","size":0},{"name":"hello.txt","resourceId":"file-1","type":"file","size":13}],"responseMetaData":{"nextCursor":null}}""";
            const string nested = """{"resources":[{"name":"nested.txt","resourceId":"file-2","type":"file","size":9}],"responseMetaData":{"nextCursor":null}}""";
            var response = new HttpResponseMessage(HttpStatusCode.OK) {
                Content = new StringContent(
                    request.RequestUri.AbsolutePath.Contains("/folders/folder-1/") ?
                        nested : sample, Encoding.UTF8, "application/json")
            };
            return Task.FromResult(response);
        }
    }

    internal static async Task LocalDavLabAsync(string rcloneExe)
    {
        const string fake = "mbx_pat_" + "012345678901234567890123456789012345";
        var api = new Api(fake, new LabApiHandler());
        await api.ListAsync(null, CancellationToken.None);
        using var gateway = new MyBoxBetaGateway(api);
        gateway.acceptLoop = Task.Run(gateway.AcceptAsync);
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), gateway.Url);
        request.Headers.TryAddWithoutValidation("Depth", "1");
        using var response = await http.SendAsync(request);
        string xml = await response.Content.ReadAsStringAsync();
        if ((int)response.StatusCode != 207 ||
            !xml.Contains("hello.txt") || !xml.Contains("docs"))
            throw new InvalidOperationException("Local MYBOX DAV folder listing failed");
        using var attemptedWrite = await http.PutAsync(gateway.Url + "fake.txt",
            new StringContent("should-not-write"));
        if (attemptedWrite.StatusCode != HttpStatusCode.Forbidden)
            throw new InvalidOperationException("MYBOX beta DAV write method was not blocked");
        if (!File.Exists(rcloneExe)) throw new FileNotFoundException(rcloneExe);
        var psi = new ProcessStartInfo(rcloneExe) {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        psi.Environment["RCLONE_CONFIG_NM_LAB_TYPE"] = "webdav";
        psi.Environment["RCLONE_CONFIG_NM_LAB_URL"] = gateway.Url;
        psi.Environment["RCLONE_CONFIG_NM_LAB_VENDOR"] = "other";
        psi.ArgumentList.Add("lsf");
        psi.ArgumentList.Add("nm_lab:");
        psi.ArgumentList.Add("--max-depth");
        psi.ArgumentList.Add("1");
        psi.ArgumentList.Add("--config");
        psi.ArgumentList.Add(Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf"));
        using var child = new Process { StartInfo = psi };
        child.Start();
        Task<string> stdout = child.StandardOutput.ReadToEndAsync();
        Task<string> stderr = child.StandardError.ReadToEndAsync();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(18));
        try { await child.WaitForExitAsync(cts.Token); }
        catch (OperationCanceledException)
        {
            if (!child.HasExited) child.Kill(entireProcessTree: true);
            throw new TimeoutException("Simulated MYBOX rclone WebDAV lsf timed out");
        }
        string output = await stdout, error = await stderr;
        if (child.ExitCode != 0 || !output.Contains("docs/") ||
            !output.Contains("hello.txt"))
            throw new InvalidOperationException(
                "Bundled rclone could not list simulated MYBOX DAV. rc=" +
                child.ExitCode + " stderr=" + error[..Math.Min(error.Length, 200)]);
        Console.WriteLine("MYBOX_BETA_LOOPBACK_PROPFIND_WRITE_DENY_RCLONE_LSF_PASS");
    }
}
