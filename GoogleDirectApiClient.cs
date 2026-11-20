using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// First building block for the future rclone-free Google Drive backend.
/// Uses the official Google Drive v3 REST API directly, a single reusable
/// HttpClient, bounded backoff and cached directory metadata.
///
/// IMPORTANT: This is not yet a WinFsp filesystem, and is intentionally
/// not selected by normal user profiles. Production selection requires
/// dedicated OAuth, cross-client change tracking and safe full file writes.
/// Existing Google mounts continue to use the established engine until
/// those invariants have tests.
/// </summary>
internal sealed class GoogleDirectApiClient
{
    internal const string FolderMime = "application/vnd.google-apps.folder";
    private readonly HttpClient _http;
    private readonly Uri _api;
    private readonly Func<CancellationToken, Task<string>> _accessToken;
    private readonly ConcurrentDictionary<string, CachedChildren> _folderCache = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _folderGates = new();
    private readonly TimeSpan _cacheTtl;
    private readonly int _maxRetries;
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;

    internal sealed record Item(string Id, string Name, bool IsFolder,
        long Size, string? Version, DateTimeOffset? Modified,
        string MimeType = "");
    private sealed record CachedChildren(DateTimeOffset Expires, IReadOnlyList<Item> Items);

    internal GoogleDirectApiClient(HttpClient http,
        Func<CancellationToken, Task<string>> accessToken,
        Uri? apiBase = null, TimeSpan? cacheTtl = null, int maxRetries = 2,
        Func<TimeSpan, CancellationToken, Task>? delay = null)
    {
        _http = http;
        _accessToken = accessToken;
        _api = apiBase ?? new Uri("https://www.googleapis.com/drive/v3/");
        if (_api.Scheme != Uri.UriSchemeHttps &&
            !(_api.IsLoopback && _api.Scheme == Uri.UriSchemeHttp))
            throw new ArgumentException("Google API must use HTTPS; HTTP only for loopback tests");
        _cacheTtl = cacheTtl ?? TimeSpan.FromSeconds(45);
        _maxRetries = Math.Clamp(maxRetries, 0, 4);
        _delay = delay ?? ((span, cancel) => Task.Delay(span, cancel));
    }

    private static string ValidId(string id)
    {
        if (id == "root") return id;
        if (string.IsNullOrWhiteSpace(id) ||
            id.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not ('-' or '_')))
            throw new ArgumentException("Invalid Google Drive file identifier");
        return id;
    }

    private static string Reason(JsonElement error)
    {
        try
        {
            var nested = error.GetProperty("error");
            if (nested.TryGetProperty("errors", out var errors) &&
                errors.ValueKind == JsonValueKind.Array && errors.GetArrayLength() > 0 &&
                errors[0].TryGetProperty("reason", out var reason))
            {
                return reason.GetString() switch
                {
                    "rateLimitExceeded" => "rateLimitExceeded",
                    "userRateLimitExceeded" => "userRateLimitExceeded",
                    "quotaExceeded" => "quotaExceeded",
                    "insufficientFilePermissions" => "insufficientFilePermissions",
                    "notFound" => "notFound",
                    _ => "other"
                };
            }
        }
        catch (Exception ex) when (ex is KeyNotFoundException or InvalidOperationException) { }
        return "unknown";
    }

    private async Task<JsonDocument> SendJsonAsync(HttpMethod method,
        string relative, object? body, CancellationToken cancel)
    {
        var uri = new Uri(_api, relative);
        for (int attempt = 0; ; attempt++)
        {
            using var request = new HttpRequestMessage(method, uri);
            string bearer = await _accessToken(cancel).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(bearer))
                throw new InvalidOperationException("Google authorization unavailable");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            request.Headers.UserAgent.ParseAdd("FileHubAIO-DirectGoogle/0.1");
            if (body is not null)
                request.Content = new StringContent(JsonSerializer.Serialize(body),
                    Encoding.UTF8, "application/json");
            using var response = await _http.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
            using var stream = await response.Content.ReadAsStreamAsync(cancel)
                .ConfigureAwait(false);
            using var parsed = await JsonDocument.ParseAsync(stream,
                cancellationToken: cancel).ConfigureAwait(false);
            if (response.IsSuccessStatusCode)
                return JsonDocument.Parse(parsed.RootElement.GetRawText());
            string reason = Reason(parsed.RootElement);
            bool throttled = response.StatusCode == HttpStatusCode.TooManyRequests ||
                (response.StatusCode == HttpStatusCode.Forbidden &&
                 reason is ("rateLimitExceeded" or "userRateLimitExceeded" or "quotaExceeded"));
            if (throttled && attempt < _maxRetries)
            {
                TimeSpan delay = TimeSpan.FromMilliseconds(350 * (1 << attempt));
                if (response.Headers.RetryAfter?.Delta is { } serverDelay &&
                    serverDelay > delay && serverDelay <= TimeSpan.FromSeconds(3))
                    delay = serverDelay;
                await _delay(delay, cancel).ConfigureAwait(false);
                continue;
            }
            // Never surface API response bodies. They may contain user
            // content, folder names, email addresses or project identifiers.
            throw new GoogleDirectApiException(
                (int)response.StatusCode, reason, throttled);
        }
    }

    private static Item ParseItem(JsonElement value)
    {
        string id = value.GetProperty("id").GetString() ?? "";
        string name = value.GetProperty("name").GetString() ?? "";
        string mime = value.TryGetProperty("mimeType", out var mt) ?
            mt.GetString() ?? "" : "";
        long size = value.TryGetProperty("size", out var z) &&
            long.TryParse(z.GetString(), out long parsedSize) ? parsedSize : 0;
        string? version = value.TryGetProperty("version", out var v) ?
            v.GetString() : null;
        DateTimeOffset? modified = value.TryGetProperty("modifiedTime", out var date) &&
            DateTimeOffset.TryParse(date.GetString(), out var parsedTime) ?
                parsedTime : null;
        return new Item(id, name, mime == FolderMime, size, version, modified, mime);
    }

    internal async Task<IReadOnlyList<Item>> ListChildrenAsync(
        string parentId, CancellationToken cancel = default)
    {
        parentId = ValidId(parentId);
        if (_folderCache.TryGetValue(parentId, out var cached) &&
            cached.Expires > DateTimeOffset.UtcNow) return cached.Items;
        var gate = _folderGates.GetOrAdd(parentId, _ => new SemaphoreSlim(1, 1));
        await gate.WaitAsync(cancel).ConfigureAwait(false);
        try
        {
            if (_folderCache.TryGetValue(parentId, out cached) &&
                cached.Expires > DateTimeOffset.UtcNow) return cached.Items;
            var files = new List<Item>();
            string? token = null;
            do
            {
                string query = "'" + parentId + "' in parents and trashed = false";
                string relative = "files?pageSize=1000&supportsAllDrives=true&" +
                    "includeItemsFromAllDrives=true&q=" + Uri.EscapeDataString(query) +
                    "&fields=" + Uri.EscapeDataString(
                        "nextPageToken,files(id,name,mimeType,size,modifiedTime,version)");
                if (token is not null)
                    relative += "&pageToken=" + Uri.EscapeDataString(token);
                using var doc = await SendJsonAsync(HttpMethod.Get, relative, null,
                    cancel).ConfigureAwait(false);
                if (doc.RootElement.TryGetProperty("files", out var list))
                    foreach (var item in list.EnumerateArray()) files.Add(ParseItem(item));
                token = doc.RootElement.TryGetProperty("nextPageToken", out var next) ?
                    next.GetString() : null;
            } while (!string.IsNullOrWhiteSpace(token));
            var result = files.AsReadOnly();
            _folderCache[parentId] =
                new CachedChildren(DateTimeOffset.UtcNow + _cacheTtl, result);
            return result;
        }
        finally { gate.Release(); }
    }

    internal void InvalidateFolder(string parentId) =>
        _folderCache.TryRemove(parentId, out _);

    internal async Task<Item?> GetFileAsync(string fileId,
        CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        using var request = new HttpRequestMessage(HttpMethod.Get,
            new Uri(_api, "files/" + fileId +
                "?fields=id,name,mimeType,size,modifiedTime,version&supportsAllDrives=true"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            await _accessToken(cancel).ConfigureAwait(false));
        using var result = await _http.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
        if (result.StatusCode == HttpStatusCode.NotFound) return null;
        if (!result.IsSuccessStatusCode)
            throw new IOException("Google file metadata HTTP " + (int)result.StatusCode);
        using var doc = await JsonDocument.ParseAsync(
            await result.Content.ReadAsStreamAsync(cancel),
            cancellationToken: cancel).ConfigureAwait(false);
        return ParseItem(doc.RootElement);
    }

    internal async Task<byte[]> ReadRangeAsync(string fileId, long offset,
        int length, CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        if (offset < 0 || length < 0 || length > 8 * 1024 * 1024)
            throw new ArgumentOutOfRangeException(nameof(length));
        if (length == 0) return [];
        using var request = new HttpRequestMessage(HttpMethod.Get,
            new Uri(_api, "files/" + fileId + "?alt=media&supportsAllDrives=true"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            await _accessToken(cancel).ConfigureAwait(false));
        request.Headers.Range = new RangeHeaderValue(offset, offset + length - 1);
        using var result = await _http.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
        if (result.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable)
            return [];
        // Never serve a 200 full file body for an offset read. This could
        // silently splice the wrong bytes into a Windows application's read.
        if (result.StatusCode != HttpStatusCode.PartialContent &&
            !(result.StatusCode == HttpStatusCode.OK && offset == 0))
            throw new IOException("Google ranged download HTTP " + (int)result.StatusCode);
        if (result.StatusCode == HttpStatusCode.PartialContent &&
            result.Content.Headers.ContentRange?.From != offset)
            throw new IOException("Google returned an unexpected Content-Range");
        using var content = await result.Content.ReadAsStreamAsync(cancel);
        using var limited = new MemoryStream(Math.Min(length, 65536));
        byte[] buffer = new byte[81920];
        int got;
        while ((got = await content.ReadAsync(buffer, cancel)) > 0)
        {
            if (limited.Length + got > length)
                throw new IOException("Google returned more bytes than requested");
            await limited.WriteAsync(buffer.AsMemory(0, got), cancel);
        }
        return limited.ToArray();
    }

    // ONLY create a new item; never use this method to overwrite an existing
    // Drive ID. Existing-file updates need an independently proven conflict
    // protocol (Drive metadata version alone is not an atomic write CAS).
    internal async Task<Item> UploadNewAsync(string parentId, string name,
        Stream data, CancellationToken cancel = default)
    {
        parentId = ValidId(parentId);
        if (string.IsNullOrWhiteSpace(name) || name.Length > 255 ||
            name.Contains('/') || name.Contains('\\') || !data.CanSeek ||
            data.Position != 0)
            throw new ArgumentException("Invalid new Google file payload");
        var upload = new Uri(_api, "../../upload/drive/v3/files?" +
            "uploadType=resumable&fields=id,name,mimeType,size,modifiedTime,version");
        using var start = new HttpRequestMessage(HttpMethod.Post, upload)
        {
            Content = new StringContent(JsonSerializer.Serialize(new
            {
                name, parents = new[] { parentId },
                mimeType = "application/octet-stream"
            }), Encoding.UTF8, "application/json")
        };
        start.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            await _accessToken(cancel).ConfigureAwait(false));
        start.Headers.TryAddWithoutValidation("X-Upload-Content-Type",
            "application/octet-stream");
        start.Headers.TryAddWithoutValidation("X-Upload-Content-Length",
            data.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
        using var accepted = await _http.SendAsync(start,
            HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
        if (!accepted.IsSuccessStatusCode || accepted.Headers.Location is not { } location)
            throw new IOException("Google resumable create initiation HTTP " +
                (int)accepted.StatusCode);
        if (!location.IsAbsoluteUri ||
            (location.Scheme != Uri.UriSchemeHttps &&
                !(_api.IsLoopback && location.IsLoopback &&
                  location.Scheme == Uri.UriSchemeHttp)) ||
            !string.Equals(location.Host, _api.Host, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google returned an unsafe upload session endpoint");
        // Chunk size is a multiple of 256 KiB, as required by Drive.
        const int chunkSize = 8 * 1024 * 1024;
        long sent = 0;
        bool empty = data.Length == 0;
        while (sent < data.Length || empty)
        {
            cancel.ThrowIfCancellationRequested();
            int size = empty ? 0 : (int)Math.Min(chunkSize, data.Length - sent);
            byte[] chunk = new byte[size];
            int total = 0;
            while (total < size)
            {
                int n = await data.ReadAsync(chunk.AsMemory(total), cancel);
                if (n == 0) throw new EndOfStreamException("Local upload stage truncated");
                total += n;
            }
            using var put = new HttpRequestMessage(HttpMethod.Put, location)
            {
                Content = new ByteArrayContent(chunk)
            };
            put.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
                await _accessToken(cancel).ConfigureAwait(false));
            put.Content.Headers.ContentType =
                new MediaTypeHeaderValue("application/octet-stream");
            put.Content.Headers.ContentRange = data.Length == 0
                ? new ContentRangeHeaderValue(0)
                : new ContentRangeHeaderValue(sent, sent + size - 1, data.Length);
            using var result = await _http.SendAsync(put,
                HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
            if (result.IsSuccessStatusCode)
            {
                using var doc = await JsonDocument.ParseAsync(
                    await result.Content.ReadAsStreamAsync(cancel),
                    cancellationToken: cancel).ConfigureAwait(false);
                var uploaded = ParseItem(doc.RootElement);
                InvalidateFolder(parentId);
                return uploaded;
            }
            if ((int)result.StatusCode != 308)
                throw new IOException("Google resumable upload HTTP " +
                    (int)result.StatusCode + "; local stage retained");
            sent += size;
            empty = false;
        }
        throw new IOException("Google resumable upload not confirmed; local stage retained");
    }

    internal async Task<Item> CreateFolderAsync(string parentId, string name,
        CancellationToken cancel = default)
    {
        parentId = ValidId(parentId);
        if (string.IsNullOrWhiteSpace(name) || name.Length > 255 ||
            name.Contains('/') || name.Contains('\\'))
            throw new ArgumentException("Invalid folder name");
        using var doc = await SendJsonAsync(HttpMethod.Post,
            "files?fields=id,name,mimeType,size,modifiedTime,version&supportsAllDrives=true",
            new { name, mimeType = FolderMime, parents = new[] { parentId } },
            cancel).ConfigureAwait(false);
        var created = ParseItem(doc.RootElement);
        if (!created.IsFolder)
            throw new IOException("Google returned a non-folder for folder creation");
        InvalidateFolder(parentId);
        return created;
    }

    internal async Task<Item> RenameAsync(string fileId, string newName,
        string parentId, CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        parentId = ValidId(parentId);
        if (string.IsNullOrWhiteSpace(newName) || newName.Length > 255 ||
            newName.Contains('/') || newName.Contains('\\'))
            throw new ArgumentException("Invalid rename target");
        using var doc = await SendJsonAsync(HttpMethod.Patch,
            "files/" + fileId +
                "?fields=id,name,mimeType,size,modifiedTime,version&supportsAllDrives=true",
            new { name = newName }, cancel).ConfigureAwait(false);
        InvalidateFolder(parentId);
        return ParseItem(doc.RootElement);
    }

    internal sealed class GoogleDirectApiException(int http, string reason, bool throttled)
        : IOException("Google Drive API HTTP " + http + " (" + reason + ")")
    {
        internal int HttpStatus { get; } = http;
        internal string Reason { get; } = reason;
        internal bool IsRateLimited { get; } = throttled;
    }
}
