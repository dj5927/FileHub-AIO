using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Native Google Drive REST transport shared by the direct WinFsp filesystem.
/// Never starts rclone. FileHub's native OAuth grant is required for normal
/// connections. Existing-file overwrite remains behind a separate safety gate.
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
    // WinFsp shares its callback pool with Explorer and Office.
    // Bound network concurrency so a slow Drive response cannot queue
    // hundreds of simultaneous callbacks and stall the whole desktop.
    private readonly SemaphoreSlim _requestSlots = new(8, 8);
    private long _unavailableUntil;
    private int _transportFailures;

    internal sealed record Item(string Id, string Name, bool IsFolder,
        long Size, string? Version, DateTimeOffset? Modified,
        string MimeType = "", string? Md5Checksum = null,
        string? HeadRevisionId = null);
    private sealed record CachedChildren(DateTimeOffset Expires,
        DateTimeOffset StaleUntil, IReadOnlyList<Item> Items);

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

    private async Task<HttpResponseMessage> SendBoundedAsync(
        HttpRequestMessage request, CancellationToken cancel)
    {
        if (Stopwatch.GetTimestamp() < Interlocked.Read(ref _unavailableUntil))
            throw new IOException("Google network is temporarily unavailable");
        if (!await _requestSlots.WaitAsync(TimeSpan.FromSeconds(2), cancel)
            .ConfigureAwait(false))
            throw new IOException("Google API request queue is busy; retry later");
        try
        {
            // Metadata/API responses must stay bounded even when a broken
            // proxy returns an HTML error or an unexpectedly huge body.
            // Keep the concurrency lease until the bounded body is loaded.
            var response = await _http.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
            try
            {
                await response.Content.LoadIntoBufferAsync(
                    8 * 1024 * 1024, cancel).ConfigureAwait(false);
                Interlocked.Exchange(ref _transportFailures, 0);
                return response;
            }
            catch
            {
                response.Dispose();
                throw;
            }
        }
        catch (TaskCanceledException ex) when (!cancel.IsCancellationRequested)
        {
            NoteTransportFailure();
            throw new IOException("Google API response timed out", ex);
        }
        catch (HttpRequestException ex)
        {
            NoteTransportFailure();
            throw new IOException("Google API network connection failed", ex);
        }
        finally { _requestSlots.Release(); }
    }

    private void NoteTransportFailure()
    {
        if (Interlocked.Increment(ref _transportFailures) >= 3)
        {
            // Brief fail-fast window prevents an offline cloud from locking
            // the entire Explorer callback pool for many parallel operations.
            Interlocked.Exchange(ref _unavailableUntil,
                Stopwatch.GetTimestamp() + Stopwatch.Frequency * 5L);
        }
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
            using var response = await SendBoundedAsync(request, cancel).ConfigureAwait(false);
            JsonDocument? parsed = null;
            try
            {
                using var stream = await response.Content.ReadAsStreamAsync(cancel)
                    .ConfigureAwait(false);
                parsed = await JsonDocument.ParseAsync(stream,
                    cancellationToken: cancel).ConfigureAwait(false);
            }
            catch (JsonException ex) when (response.IsSuccessStatusCode)
            {
                throw new IOException("Google API returned malformed metadata", ex);
            }
            catch (JsonException) when (!response.IsSuccessStatusCode)
            {
                // A proxy or gateway may return HTML/empty error bodies.
                // Preserve the HTTP status; never parse or log its payload.
            }
            using (parsed)
            {
            if (response.IsSuccessStatusCode)
            {
                if (parsed!.RootElement.ValueKind != JsonValueKind.Object)
                    throw new IOException("Google API response was not a metadata object");
                return JsonDocument.Parse(parsed.RootElement.GetRawText());
            }
            string reason = parsed is null ? "unknown" : Reason(parsed.RootElement);
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
    }

    private static Item ParseItem(JsonElement value)
    {
        try
        {
        string id = value.GetProperty("id").GetString() ?? "";
        string name = value.GetProperty("name").GetString() ?? "";
        string mime = value.TryGetProperty("mimeType", out var mt) ?
            mt.GetString() ?? "" : "";
        long size = value.TryGetProperty("size", out var z) &&
            long.TryParse(z.GetString(), out long parsedSize) ? parsedSize : 0;
        string? version = value.TryGetProperty("version", out var v) ?
            v.GetString() : null;
        string? md5 = value.TryGetProperty("md5Checksum", out var check) ?
            check.GetString() : null;
        string? head = value.TryGetProperty("headRevisionId", out var rev) ?
            rev.GetString() : null;
        DateTimeOffset? modified = value.TryGetProperty("modifiedTime", out var date) &&
            DateTimeOffset.TryParse(date.GetString(), out var parsedTime) ?
                parsedTime : null;
        return new Item(id, name, mime == FolderMime, size, version,
            modified, mime, md5, head);
        }
        catch (Exception ex) when (ex is KeyNotFoundException or
            InvalidOperationException or FormatException)
        {
            throw new IOException("Google API returned invalid file metadata", ex);
        }
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
            try
            {
                string? token = null;
                var seenPageTokens = new HashSet<string>(StringComparer.Ordinal);
                int pageCount = 0;
                do
                {
                    // Repeated pagination tokens must never spin forever
                    // on an Explorer callback. 10000 pages cover up to
                    // ten million files before failing safely.
                    if (++pageCount > 10000 ||
                        (token is not null && !seenPageTokens.Add(token)))
                        throw new IOException(
                            "Google folder pagination did not make forward progress");
                    string query = "'" + parentId + "' in parents and trashed = false";
                    string relative = "files?pageSize=1000&supportsAllDrives=true&" +
                        "includeItemsFromAllDrives=true&q=" + Uri.EscapeDataString(query) +
                        "&fields=" + Uri.EscapeDataString(
                            "nextPageToken,files(id,name,mimeType,size,modifiedTime,version,md5Checksum,headRevisionId)");
                    if (token is not null)
                        relative += "&pageToken=" + Uri.EscapeDataString(token);
                    using var doc = await SendJsonAsync(HttpMethod.Get, relative, null,
                        cancel).ConfigureAwait(false);
                    if (!doc.RootElement.TryGetProperty("files", out var list) ||
                        list.ValueKind != JsonValueKind.Array)
                        throw new IOException(
                            "Google directory reply did not contain a valid files list");
                    foreach (var item in list.EnumerateArray()) files.Add(ParseItem(item));
                    token = null;
                    if (doc.RootElement.TryGetProperty("nextPageToken", out var next))
                    {
                        if (next.ValueKind == JsonValueKind.String)
                            token = next.GetString();
                        else if (next.ValueKind != JsonValueKind.Null)
                            throw new IOException("Google directory pagination token was invalid");
                    }
                } while (!string.IsNullOrWhiteSpace(token));
            }
            catch (IOException ex) when (!cancel.IsCancellationRequested &&
                cached is not null && cached.StaleUntil > DateTimeOffset.UtcNow &&
                (ex is not GoogleDirectApiException apiError ||
                    apiError.IsRateLimited || apiError.HttpStatus >= 500))
            {
                // Temporary Google outage must not make Explorer display an
                // apparently empty folder. Only serve previously VERIFIED
                // metadata for up to five minutes; never cache permission
                // failures or other non-transient Google HTTP errors.
                return cached.Items;
            }
            var result = files.AsReadOnly();
            var current = DateTimeOffset.UtcNow;
            _folderCache[parentId] = new CachedChildren(
                current + _cacheTtl, current + TimeSpan.FromMinutes(5), result);
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
                "?fields=id,name,mimeType,size,modifiedTime,version,md5Checksum,headRevisionId&supportsAllDrives=true"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            await _accessToken(cancel).ConfigureAwait(false));
        using var result = await SendBoundedAsync(request, cancel).ConfigureAwait(false);
        if (result.StatusCode == HttpStatusCode.NotFound) return null;
        if (!result.IsSuccessStatusCode)
            throw new IOException("Google file metadata HTTP " + (int)result.StatusCode);
        try
        {
            using var doc = await JsonDocument.ParseAsync(
                await result.Content.ReadAsStreamAsync(cancel),
                cancellationToken: cancel).ConfigureAwait(false);
            return ParseItem(doc.RootElement);
        }
        catch (JsonException ex)
        {
            throw new IOException("Google file metadata response was malformed", ex);
        }
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
        if (Stopwatch.GetTimestamp() < Interlocked.Read(ref _unavailableUntil))
            throw new IOException("Google network is temporarily unavailable");
        if (!await _requestSlots.WaitAsync(TimeSpan.FromSeconds(2), cancel)
            .ConfigureAwait(false))
            throw new IOException("Google API request queue is busy; retry later");
        try
        {
        // ContentRead would buffer the ENTIRE response before validating
        // Range. A proxy ignoring Range on a multi-GB file could otherwise
        // exhaust RAM and freeze Explorer. Hold the slot while streaming and
        // stop as soon as one byte beyond the requested length is received.
        using var result = await _http.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
        Interlocked.Exchange(ref _transportFailures, 0);
        if (result.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable)
            throw new IOException(
                "Google file size changed; ranged read rejected rather than returning false EOF");
        // Never serve a 200 full file body for an offset read. This could
        // silently splice the wrong bytes into a Windows application's read.
        if (result.StatusCode != HttpStatusCode.PartialContent &&
            !(result.StatusCode == HttpStatusCode.OK && offset == 0))
            throw new IOException("Google ranged download HTTP " + (int)result.StatusCode);
        if (result.StatusCode == HttpStatusCode.PartialContent &&
            (result.Content.Headers.ContentRange?.From != offset ||
             result.Content.Headers.ContentRange?.To != offset + length - 1))
            throw new IOException("Google returned a truncated or unexpected Content-Range");
        if (result.Content.Headers.ContentLength > length)
            throw new IOException("Google ignored byte range or returned excess data");
        using var content = await result.Content.ReadAsStreamAsync(cancel);
        using var limited = new MemoryStream(Math.Min(length, 65536));
        byte[] buffer = new byte[81920];
        int got;
        while ((got = await content.ReadAsync(buffer.AsMemory(0,
            (int)Math.Min(buffer.Length, length - limited.Length + 1)), cancel)) > 0)
        {
            if (limited.Length + got > length)
                throw new IOException("Google returned more bytes than requested");
            await limited.WriteAsync(buffer.AsMemory(0, got), cancel);
        }
        if (limited.Length != length)
            throw new IOException("Google ranged file read was shorter than requested");
        return limited.ToArray();
        }
        catch (TaskCanceledException ex) when (!cancel.IsCancellationRequested)
        {
            NoteTransportFailure();
            throw new IOException("Google ranged download timed out", ex);
        }
        catch (HttpRequestException ex)
        {
            NoteTransportFailure();
            throw new IOException("Google ranged download network failed", ex);
        }
        finally { _requestSlots.Release(); }
    }

    // ONLY create a new item; never use this method to overwrite an existing
    // Drive ID. Existing-file updates need an independently proven conflict
    // protocol (Drive metadata version alone is not an atomic write CAS).
    internal async Task<string> GenerateUploadIdAsync(CancellationToken cancel = default)
    {
        using var result = await SendJsonAsync(HttpMethod.Get,
            "files/generateIds?count=1&space=drive", null, cancel).ConfigureAwait(false);
        string id = result.RootElement.GetProperty("ids")[0].GetString() ?? "";
        return ValidId(id);
    }

    internal async Task<Item> UploadNewAsync(string parentId, string name,
        Stream data, CancellationToken cancel = default, string? generatedId = null)
    {
        parentId = ValidId(parentId);
        if (generatedId is not null) generatedId = ValidId(generatedId);
        if (string.IsNullOrWhiteSpace(name) || name.Length > 255 ||
            name.Contains('/') || name.Contains('\\') || !data.CanSeek ||
            data.Position != 0)
            throw new ArgumentException("Invalid new Google file payload");
        var upload = new Uri(_api, "../../upload/drive/v3/files?" +
            "uploadType=resumable&supportsAllDrives=true&fields=id,name,mimeType,size,modifiedTime,version,md5Checksum");
        string allocatedId = generatedId ??
            await GenerateUploadIdAsync(cancel).ConfigureAwait(false);
        using var start = new HttpRequestMessage(HttpMethod.Post, upload)
        {
            Content = new StringContent(JsonSerializer.Serialize(new Dictionary<string, object>
            {
                ["name"] = name,
                ["parents"] = new[] { parentId },
                ["mimeType"] = "application/octet-stream",
                ["id"] = allocatedId
            }), Encoding.UTF8, "application/json")
        };
        start.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
            await _accessToken(cancel).ConfigureAwait(false));
        start.Headers.TryAddWithoutValidation("X-Upload-Content-Type",
            "application/octet-stream");
        start.Headers.TryAddWithoutValidation("X-Upload-Content-Length",
            data.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
        using var accepted = await SendBoundedAsync(start, cancel).ConfigureAwait(false);
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
        int stalled = 0;
        while (sent < data.Length || empty)
        {
            cancel.ThrowIfCancellationRequested();
            data.Position = sent;
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
            using var result = await SendBoundedAsync(put, cancel).ConfigureAwait(false);
            if (result.IsSuccessStatusCode)
            {
                using var doc = await JsonDocument.ParseAsync(
                    await result.Content.ReadAsStreamAsync(cancel),
                    cancellationToken: cancel).ConfigureAwait(false);
                var uploaded = ParseItem(doc.RootElement);
                if (uploaded.Id != allocatedId)
                    throw new IOException(
                        "Google upload returned an unexpected file ID; local recovery retained");
                InvalidateFolder(parentId);
                return uploaded;
            }
            if ((int)result.StatusCode != 308)
                throw new IOException("Google resumable upload HTTP " +
                    (int)result.StatusCode + "; local stage retained");
            // 308 is not a guarantee that the whole requested chunk was
            // stored. Drive reports the ACKNOWLEDGED range in its response
            // header. Only advance to that offset, never skip unaccepted
            // data. This also supports partial-chunk recovery.
            long acknowledged = -1;
            if (result.Headers.TryGetValues("Range", out var ranges))
            {
                string raw = ranges.FirstOrDefault() ?? "";
                var match = System.Text.RegularExpressions.Regex.Match(
                    raw, @"^bytes=0-(\d+)$");
                if (match.Success &&
                    long.TryParse(match.Groups[1].Value, out long last))
                    acknowledged = last + 1;
            }
            if (acknowledged < sent || acknowledged > data.Length)
                throw new IOException(
                    "Google resumable upload acknowledged invalid byte range");
            if (acknowledged == sent && ++stalled >= 3)
                throw new IOException(
                    "Google resumable upload did not advance; local recovery retained");
            if (acknowledged > sent) stalled = 0;
            sent = acknowledged;
            empty = false;
        }
        throw new IOException("Google resumable upload not confirmed; local stage retained");
    }

    internal async Task<Item> PinCurrentBinaryRevisionAsync(string fileId,
        string expectedHeadRevision, string expectedMd5,
        CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        string revision = ValidId(expectedHeadRevision);
        var current = await GetFileAsync(fileId, cancel).ConfigureAwait(false);
        if (current is null || current.IsFolder ||
            current.MimeType.StartsWith("application/vnd.google-apps.",
                StringComparison.OrdinalIgnoreCase) ||
            current.HeadRevisionId != revision ||
            !string.Equals(current.Md5Checksum, expectedMd5,
                StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google existing file changed; refusing edit");
        using var pinned = await SendJsonAsync(HttpMethod.Patch,
            "files/" + fileId + "/revisions/" + revision +
                "?fields=id,keepForever,md5Checksum",
            new { keepForever = true }, cancel).ConfigureAwait(false);
        if (pinned.RootElement.GetProperty("id").GetString() != revision ||
            pinned.RootElement.GetProperty("keepForever").GetBoolean() != true)
            throw new IOException("Google previous binary revision was not pinned");
        var afterPin = await GetFileAsync(fileId, cancel).ConfigureAwait(false);
        if (afterPin?.HeadRevisionId != revision ||
            !string.Equals(afterPin.Md5Checksum, expectedMd5,
                StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google content changed while backing up revision");
        // A revision remains permanently recoverable. This is NOT an
        // atomic compare-and-swap for concurrent Google clients.
        return afterPin;
    }

    internal async Task<Item> UploadExistingAsync(string fileId, string expectedMd5,
        string expectedRevision, Stream data, CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        if (!data.CanSeek || data.Position != 0)
            throw new ArgumentException("Existing Google upload requires a seekable stream at zero");
        var current = await GetFileAsync(fileId, cancel).ConfigureAwait(false);
        if (current is null || current.HeadRevisionId != expectedRevision ||
            !string.Equals(current.Md5Checksum, expectedMd5,
                StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google existing file changed before uploading new revision");
        Uri upload = new(_api, "../../upload/drive/v3/files/" + fileId +
            "?uploadType=resumable&supportsAllDrives=true&" +
            "fields=id,name,mimeType,size,modifiedTime,version,md5Checksum,headRevisionId");
        using var request = new HttpRequestMessage(HttpMethod.Patch, upload)
        { Content = new StringContent("{}", Encoding.UTF8, "application/json") };
        request.Headers.Authorization = new AuthenticationHeaderValue(
            "Bearer", await _accessToken(cancel).ConfigureAwait(false));
        request.Headers.TryAddWithoutValidation("X-Upload-Content-Type",
            "application/octet-stream");
        request.Headers.TryAddWithoutValidation("X-Upload-Content-Length",
            data.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
        using var initiated = await SendBoundedAsync(request, cancel).ConfigureAwait(false);
        if (!initiated.IsSuccessStatusCode || initiated.Headers.Location is not { } location)
            throw new IOException("Google existing revision session failed HTTP " +
                (int)initiated.StatusCode);
        if (!location.IsAbsoluteUri ||
            !string.Equals(location.Host, _api.Host, StringComparison.OrdinalIgnoreCase) ||
            (location.Scheme != Uri.UriSchemeHttps &&
                !(_api.IsLoopback && location.IsLoopback &&
                  location.Scheme == Uri.UriSchemeHttp)))
            throw new IOException("Invalid Google existing revision upload endpoint");
        // Re-check immediately before sending bytes. Another client can
        // still race this point, but the previous revision is pinned.
        current = await GetFileAsync(fileId, cancel).ConfigureAwait(false);
        if (current?.HeadRevisionId != expectedRevision ||
            !string.Equals(current?.Md5Checksum, expectedMd5,
                StringComparison.OrdinalIgnoreCase))
            throw new IOException("Google existing file changed during upload setup");
        const int chunkSize = 8 * 1024 * 1024;
        long sent = 0;
        int stalls = 0;
        bool empty = data.Length == 0;
        while (sent < data.Length || empty)
        {
            data.Position = sent;
            int size = empty ? 0 : (int)Math.Min(chunkSize, data.Length - sent);
            byte[] chunk = new byte[size];
            int loaded = 0;
            while (loaded < size)
            {
                int got = await data.ReadAsync(chunk.AsMemory(loaded), cancel);
                if (got <= 0) throw new EndOfStreamException("Edited Google file truncated");
                loaded += got;
            }
            using var put = new HttpRequestMessage(HttpMethod.Put, location)
            { Content = new ByteArrayContent(chunk) };
            put.Headers.Authorization = new AuthenticationHeaderValue("Bearer",
                await _accessToken(cancel).ConfigureAwait(false));
            put.Content.Headers.ContentType =
                new MediaTypeHeaderValue("application/octet-stream");
            put.Content.Headers.ContentRange = empty
                ? new ContentRangeHeaderValue(0)
                : new ContentRangeHeaderValue(sent, sent + size - 1, data.Length);
            using var response = await SendBoundedAsync(put, cancel).ConfigureAwait(false);
            if (response.IsSuccessStatusCode)
            {
                using var doc = await JsonDocument.ParseAsync(
                    await response.Content.ReadAsStreamAsync(cancel),
                    cancellationToken: cancel).ConfigureAwait(false);
                var result = ParseItem(doc.RootElement);
                if (result.Id != fileId)
                    throw new IOException("Google modified a different file than expected");
                return result;
            }
            if ((int)response.StatusCode != 308)
                throw new IOException("Google existing revision upload HTTP " +
                    (int)response.StatusCode + "; recovery retained");
            string range = response.Headers.TryGetValues("Range", out var ranges)
                ? ranges.FirstOrDefault() ?? "" : "";
            var match = System.Text.RegularExpressions.Regex.Match(
                range, @"^bytes=0-(\d+)$");
            if (!match.Success ||
                !long.TryParse(match.Groups[1].Value, out long last))
                throw new IOException("Google resumable update lacks a valid byte-range ACK");
            long acknowledged = last + 1;
            if (acknowledged < sent || acknowledged > data.Length ||
                (acknowledged == sent && ++stalls > 2))
                throw new IOException("Google resumable update failed to advance");
            if (acknowledged > sent) stalls = 0;
            sent = acknowledged;
            empty = false;
        }
        throw new IOException("Google existing revision upload not confirmed");
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

    internal async Task<Item> MoveAsync(string fileId, string oldParentId,
        string newParentId, string newName,
        CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        oldParentId = ValidId(oldParentId);
        newParentId = ValidId(newParentId);
        if (string.IsNullOrWhiteSpace(newName) || newName.Length > 255 ||
            newName.Contains('/') || newName.Contains('\\'))
            throw new ArgumentException("Invalid Google Drive move target");
        string query = "files/" + fileId +
            "?supportsAllDrives=true&addParents=" +
            Uri.EscapeDataString(newParentId) + "&removeParents=" +
            Uri.EscapeDataString(oldParentId) +
            "&fields=id,name,mimeType,size,modifiedTime,version";
        using var doc = await SendJsonAsync(HttpMethod.Patch,
            query, new { name = newName }, cancel).ConfigureAwait(false);
        InvalidateFolder(oldParentId);
        InvalidateFolder(newParentId);
        return ParseItem(doc.RootElement);
    }

    internal async Task TrashAsync(string fileId, string parentId,
        string? expectedVersion, CancellationToken cancel = default)
    {
        fileId = ValidId(fileId);
        parentId = ValidId(parentId);
        var current = await GetFileAsync(fileId, cancel).ConfigureAwait(false);
        if (current is null)
            throw new FileNotFoundException("Google deletion target was removed");
        if (!string.IsNullOrEmpty(expectedVersion) &&
            !string.Equals(expectedVersion, current.Version, StringComparison.Ordinal))
            throw new IOException(
                "Google file changed since Explorer opened it; keeping Drive original");
        // Trash is reversible. No permanent DELETE is issued. Drive v3 does
        // not promise this metadata version preflight is an atomic CAS.
        using var doc = await SendJsonAsync(HttpMethod.Patch,
            "files/" + fileId +
                "?supportsAllDrives=true&fields=id,name,mimeType,size,version",
            new { trashed = true }, cancel).ConfigureAwait(false);
        if (doc.RootElement.GetProperty("id").GetString() != fileId)
            throw new IOException("Google trash response returned wrong file ID");
        InvalidateFolder(parentId);
    }

    internal sealed class GoogleDirectApiException(int http, string reason, bool throttled)
        : IOException("Google Drive API HTTP " + http + " (" + reason + ")")
    {
        internal int HttpStatus { get; } = http;
        internal string Reason { get; } = reason;
        internal bool IsRateLimited { get; } = throttled;
    }
}
