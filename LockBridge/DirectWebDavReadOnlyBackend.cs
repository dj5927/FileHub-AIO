using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using System.Xml.Linq;

namespace NASMount.LockBridge;

/// <summary>
/// First, read-only proof of the RaiDrive-style architecture:
/// WinFsp -> FileHub provider -> WebDAV HTTP -> NAS, without mounting rclone
/// as a second filesystem. This is a loopback-only development backend,
/// deliberately not connected to production profiles until writes/locks,
/// crash-recovery and cache invalidation are proven independently.
/// </summary>
internal sealed class DirectWebDavReadOnlyBackend : IDisposable
{
    internal sealed record Entry(string Path, string Name, bool IsDirectory,
        long Size, DateTime ModifiedUtc, string? ETag = null);
    internal sealed record OfficeOwnerSnapshot(Entry Entry, byte[]? Bytes);
    internal const int MaxOfficeOwnerBytes = 4096;
    internal sealed record StorageQuota(long TotalBytes, long FreeBytes);

    private sealed record TimedEntry(Entry Value, DateTime ExpiresUtc);
    private sealed record TimedDirectory(Entry[] Values, DateTime ExpiresUtc);
    private readonly Uri _root;
    private readonly HttpClient _http;
    private readonly string _localCache;
    private readonly TimeSpan _cacheAge;
    private readonly long _cacheLimit;
    private readonly System.Threading.Timer _cacheTimer;
    private readonly ConcurrentDictionary<string, DateTime> _cacheAccess = new();
    private int _cachePruning;
    private int _disposed;
    private readonly ConcurrentDictionary<string, TimedEntry> _metadata =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, TimedDirectory> _directories =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DateTime> _missing =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, object> _fileGates =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, string> _cachedExecutables =
        new(StringComparer.OrdinalIgnoreCase);
    private static readonly TimeSpan DirectoryTtl = TimeSpan.FromSeconds(8);
    private static readonly TimeSpan MetadataTtl = TimeSpan.FromSeconds(15);
    private const long MaxCachedExecutableSize = 64L * 1024 * 1024;

    internal DirectWebDavReadOnlyBackend(Uri root)
        : this(root, null, "", requireLoopback: true)
    {
    }

    internal DirectWebDavReadOnlyBackend(Uri root, HttpClient testHttp)
        : this(root, null, "", requireLoopback: true, testHttp: testHttp)
    {
    }

    // Only a persisted, user-configured WebDAV profile may authorize a
    // non-loopback host. The command-line test URI never gains this ability.
    internal DirectWebDavReadOnlyBackend(NasProfile profile, string password)
        : this(ExcelAtomicBridge.ProfileRoot(profile), profile, password,
            requireLoopback: false)
    {
    }

    private DirectWebDavReadOnlyBackend(Uri root, NasProfile? profile,
        string password, bool requireLoopback, HttpClient? testHttp = null)
    {
        if ((requireLoopback && (!root.IsLoopback || root.Scheme != Uri.UriSchemeHttp)) ||
            root.UserInfo.Length > 0 || root.Query.Length > 0 ||
            root.Fragment.Length > 0 ||
            (profile is not null && (profile.Protocol != "WebDAV" ||
                profile.Host.Length == 0 ||
                (profile.IsAnonymous == false && password.Length == 0))))
            throw new ArgumentException("Unsafe direct WebDAV endpoint or credentials");
        _root = new Uri(root.AbsoluteUri.TrimEnd('/') + "/");
        _http = testHttp ?? (profile is not null
            ? WebDavTlsPolicy.CreateClient(profile, TimeSpan.FromSeconds(25),
                allowRedirect: false)
            : new HttpClient(new HttpClientHandler {
                AllowAutoRedirect = false,
                UseCookies = false,
                AutomaticDecompression = DecompressionMethods.None
            }) { Timeout = TimeSpan.FromSeconds(25) });
        if (profile is { IsAnonymous: false })
            _http.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(
                        profile.Username + ":" + password)));
        var cache = profile is null ? new AppConfig() : LocalCachePolicy.Current();
        _cacheAge = TimeSpan.FromMinutes(LocalCachePolicy.Minutes(cache));
        _cacheLimit = LocalCachePolicy.GiB(cache) * 1073741824L;
        _localCache = profile is null
            ? Path.Combine(Path.GetTempPath(), "FileHubDirectWebDavLab_" + Guid.NewGuid().ToString("N"))
            : Path.Combine(LocalCachePolicy.Root(cache), "webdav-read", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_localCache);
        _cacheTimer = new System.Threading.Timer(_ => PruneReadCache(),
            null, TimeSpan.FromSeconds(30), TimeSpan.FromSeconds(30));
    }

    internal static string Normalize(string path)
    {
        if (path.IndexOf('\0') >= 0) throw new ArgumentException("NUL in path");
        var parts = path.Replace('\\', '/').Split('/',
            StringSplitOptions.RemoveEmptyEntries);
        if (parts.Any(p => p is "." or ".." || p.Contains(':')))
            throw new ArgumentException("Unsafe WebDAV path");
        return "/" + string.Join('/', parts);
    }

    private Uri RemoteUrl(string path)
    {
        string escaped = string.Join('/', Normalize(path).Split('/',
            StringSplitOptions.RemoveEmptyEntries).Select(Uri.EscapeDataString));
        return new Uri(_root, escaped);
    }

    internal static StorageQuota? ParseStorageQuota(string xml)
    {
        // RFC 4331 quota properties are absent from ordinary allprop.
        // Parse only successful DAV:propstat entries. A 404 or missing
        // property is UNKNOWN, never "zero remaining".
        using var input = new StringReader(xml);
        using var reader = XmlReader.Create(input, new XmlReaderSettings
        {
            DtdProcessing = DtdProcessing.Prohibit,
            XmlResolver = null,
            MaxCharactersInDocument = 65536
        });
        XDocument document = XDocument.Load(reader);
        XNamespace dav = "DAV:";
        foreach (var response in document.Descendants(dav + "response"))
        foreach (var propstat in response.Elements(dav + "propstat"))
        {
            if (!(propstat.Element(dav + "status")?.Value ?? "")
                    .Contains(" 200 ", StringComparison.Ordinal)) continue;
            var properties = propstat.Element(dav + "prop");
            if (properties is null) continue;
            if (!long.TryParse(properties.Element(dav + "quota-used-bytes")?.Value,
                    NumberStyles.None, CultureInfo.InvariantCulture, out long used) ||
                !long.TryParse(properties.Element(dav + "quota-available-bytes")?.Value,
                    NumberStyles.None, CultureInfo.InvariantCulture, out long free) ||
                used < 0 || free < 0 || used > long.MaxValue - free)
                continue;
            long total = used + free;
            if (total > 0) return new StorageQuota(total, free);
        }
        return null;
    }

    internal async Task<StorageQuota?> GetStorageQuotaAsync(
        CancellationToken cancel = default)
    {
        // The exact configured root only; no directory enumeration or
        // NAS vendor/port heuristics. Called from an async quota worker.
        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"),
            RemoteUrl("/"));
        request.Headers.TryAddWithoutValidation("Depth", "0");
        request.Content = new StringContent(
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:quota-available-bytes/><d:quota-used-bytes/>" +
            "</d:prop></d:propfind>", Encoding.UTF8, "application/xml");
        using var reply = await _http.SendAsync(request,
            HttpCompletionOption.ResponseHeadersRead, cancel).ConfigureAwait(false);
        if ((int)reply.StatusCode != 207) return null;
        await reply.Content.LoadIntoBufferAsync(65536, cancel).ConfigureAwait(false);
        string xml = await reply.Content.ReadAsStringAsync(cancel)
            .ConfigureAwait(false);
        try { return ParseStorageQuota(xml); }
        catch (XmlException) { return null; }
    }

    private Entry[] PropFind(string path, int depth)
    {
        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"),
            RemoteUrl(path));
        request.Headers.TryAddWithoutValidation("Depth", depth.ToString());
        request.Content = new StringContent(
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:allprop/></d:propfind>",
            Encoding.UTF8, "application/xml");
        using var response = _http.Send(request);
        if (response.StatusCode == HttpStatusCode.NotFound) return [];
        if ((int)response.StatusCode != 207)
            throw new IOException("WebDAV PROPFIND HTTP " + (int)response.StatusCode);
        using var body = response.Content.ReadAsStream();
        XDocument doc = XDocument.Load(body);
        XNamespace dav = "DAV:";
        var entries = new List<Entry>();
        foreach (var resource in doc.Descendants(dav + "response"))
        {
            string? href = resource.Element(dav + "href")?.Value;
            if (string.IsNullOrWhiteSpace(href)) continue;
            Uri resolved;
            try { resolved = new Uri(_root, href); }
            catch (UriFormatException) { continue; }
            if (resolved.Host != _root.Host || resolved.Port != _root.Port ||
                !resolved.AbsolutePath.StartsWith(_root.AbsolutePath,
                    StringComparison.OrdinalIgnoreCase)) continue;
            string relative = Uri.UnescapeDataString(resolved.AbsolutePath[
                _root.AbsolutePath.Length..]);
            string name;
            string canonical;
            try
            {
                canonical = Normalize(relative);
                name = canonical == "/" ? "" : canonical.Split('/').Last();
            }
            catch (ArgumentException) { continue; }
            var propstat = resource.Elements(dav + "propstat").FirstOrDefault(e =>
                (e.Element(dav + "status")?.Value ?? "").Contains(" 200 "));
            var prop = propstat?.Element(dav + "prop");
            if (prop is null) continue;
            bool dir = prop.Element(dav + "resourcetype")?.Element(dav + "collection") != null;
            long size = 0;
            long.TryParse(prop.Element(dav + "getcontentlength")?.Value,
                out size);
            // Missing Last-Modified is an unknown timestamp, not the clock
            // time of each PROPFIND. Fabricating UtcNow made the watcher
            // repeatedly tell Excel that another editor changed the file.
            DateTime modified = DateTime.UnixEpoch;
            if (DateTimeOffset.TryParse(prop.Element(dav + "getlastmodified")?.Value,
                    out var parsed)) modified = parsed.UtcDateTime;
            string? etag = resource.Elements(dav + "propstat")
                .Where(e => (e.Element(dav + "status")?.Value ?? "").Contains(" 200 "))
                .Select(e => e.Element(dav + "prop")?.Element(dav + "getetag")?.Value?.Trim())
                .FirstOrDefault(e => !string.IsNullOrEmpty(e));
            entries.Add(new Entry(canonical, name, dir, Math.Max(0, size), modified, etag));
        }
        return entries.ToArray();
    }

    internal Entry? Stat(string path)
    {
        string p = Normalize(path);
        if (p == "/") return new Entry("/", "", true, 0, DateTime.UtcNow);
        if (_missing.TryGetValue(p, out var absentUntil) &&
            absentUntil > DateTime.UtcNow) return null;
        if (_metadata.TryGetValue(p, out var cached) &&
            cached.ExpiresUtc > DateTime.UtcNow) return cached.Value;
        Entry? result = PropFind(p, 0).FirstOrDefault(e =>
            e.Path.Equals(p, StringComparison.OrdinalIgnoreCase));
        // Some NAS implementations omit Office temporary files from PROPFIND.
        // Exact GET can establish presence without trusting directory caches.
        if (result is null && ExcelOwnerInterop.IsOwnerFile(p))
            result = ReadOfficeOwnerFresh(p)?.Entry;
        if (result is not null)
        {
            _missing.TryRemove(p, out _);
            _metadata[p] = new TimedEntry(result, DateTime.UtcNow + MetadataTtl);
        }
        else _missing[p] = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        return result;
    }

    // Never use the 15-second metadata cache for destructive operations.
    internal Entry? StatFresh(string path)
    {
        Invalidate(path);
        return Stat(path);
    }

    // A small Excel owner must be discoverable by another mount even when
    // no strong validator is advertised. No ordinary file uses this path.
    internal OfficeOwnerSnapshot? ReadOfficeOwnerFresh(string path)
    {
        string key = Normalize(path);
        if (!ExcelOwnerInterop.IsOwnerFile(key))
            throw new ArgumentException("Expected an Excel owner path");
        using var request = new HttpRequestMessage(HttpMethod.Get, RemoteUrl(key));
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        using var response = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (response.StatusCode is HttpStatusCode.NotFound or HttpStatusCode.Gone)
            return null;
        if (response.StatusCode != HttpStatusCode.OK)
            throw new IOException("Excel owner GET HTTP " + (int)response.StatusCode);
        long? declared = response.Content.Headers.ContentLength;
        byte[]? bytes = null;
        long size;
        if (declared > MaxOfficeOwnerBytes)
        {
            size = declared.Value;
        }
        else
        {
            using var body = response.Content.ReadAsStream();
            byte[] buffer = new byte[MaxOfficeOwnerBytes + 1];
            int count = 0;
            while (count < buffer.Length)
            {
                int n = body.Read(buffer, count, buffer.Length - count);
                if (n == 0) break;
                count += n;
            }
            size = declared ?? count;
            if (count <= MaxOfficeOwnerBytes && size == count)
                bytes = buffer[..count];
        }
        var entry = new Entry(key, key.Split('/').Last(), false, size,
            response.Content.Headers.LastModified?.UtcDateTime ?? DateTime.UnixEpoch,
            response.Headers.ETag?.ToString());
        return new OfficeOwnerSnapshot(entry, bytes);
    }

    // A verified, conditional new-file PUT does not need a separate
    // PROPFIND just to repeat the file revision which the NAS included in
    // the PUT response. Reuse the known entry and invalidate the parent list.
    internal void RecordCommittedNewFile(Entry committed)
    {
        string path = Normalize(committed.Path);
        _missing.TryRemove(path, out _);
        _metadata[path] = new TimedEntry(committed, DateTime.UtcNow + MetadataTtl);
        string parent = Normalize(Path.GetDirectoryName(path.Replace('/', '\\')) ?? "/");
        _directories.TryRemove(parent, out _);
    }

    // WinFsp's Create-if-absent is ultimately guarded by the server's
    // already-verified If-None-Match:* PUT precondition. An additional
    // network PROPFIND for every new photo cannot prevent a race; reject
    // a known existing file cheaply and let the conditional PUT decide
    // any concurrent creation that occurs between those calls.
    internal Entry? KnownEntry(string path)
    {
        string p = Normalize(path);
        return _metadata.TryGetValue(p, out var cached) &&
               cached.ExpiresUtc > DateTime.UtcNow ? cached.Value : null;
    }

    internal Entry[] List(string folder)
    {
        string p = Normalize(folder);
        if (_directories.TryGetValue(p, out var cached) &&
            cached.ExpiresUtc > DateTime.UtcNow) return cached.Values;
        Entry[] result = PropFind(p, 1).Where(e =>
            !e.Path.Equals(p, StringComparison.OrdinalIgnoreCase) &&
            Normalize(Path.GetDirectoryName(e.Path.Replace('/', '\\')) ?? "")
                .Equals(p, StringComparison.OrdinalIgnoreCase))
            .OrderBy(e => e.Name, StringComparer.OrdinalIgnoreCase).ToArray();
        var expiry = DateTime.UtcNow + DirectoryTtl;
        _directories[p] = new TimedDirectory(result, expiry);
        foreach (var e in result)
        {
            _missing.TryRemove(e.Path, out _);
            _metadata[e.Path] = new TimedEntry(e, DateTime.UtcNow + MetadataTtl);
        }
        return result;
    }

    // Polling must bypass foreground metadata caches and query the NAS.
    internal Entry[] RefreshList(string folder)
    {
        string path = Normalize(folder);
        _directories.TryRemove(path, out _);
        return List(path);
    }

    internal void Invalidate(string path)
    {
        string p = Normalize(path);
        _metadata.TryRemove(p, out _);
        _missing.TryRemove(p, out _);
        _directories.TryRemove(p, out _);
        string parent = Normalize(Path.GetDirectoryName(p.Replace('/', '\\')) ?? "/");
        _directories.TryRemove(parent, out _);
    }

    internal byte[] Read(Entry entry, ulong offset, int wanted)
    {
        if (entry.IsDirectory || offset >= (ulong)entry.Size || wanted <= 0)
            return [];
        int count = (int)Math.Min((long)wanted, entry.Size - (long)offset);
        // Image viewers often issue many tiny overlapping reads for EXIF,
        // thumbnails and decoding. Repeating a blocking WebDAV GET for each
        // range can leave Explorer showing "Not responding" after opening
        // the image. Serve eligible immutable-revision images from the
        // same local versioned read cache already used for EXE/DLL.
        string extension = Path.GetExtension(entry.Name);
        bool cachedContent = extension.Equals(".exe", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".dll", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".jpg", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".jpeg", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".png", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".webp", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".gif", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".bmp", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".heic", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".tif", StringComparison.OrdinalIgnoreCase) ||
            extension.Equals(".tiff", StringComparison.OrdinalIgnoreCase);
        if (cachedContent &&
            entry.Size <= MaxCachedExecutableSize)
        {
            string id = CacheId(entry);
            // Hold the same per-item gate across download AND read. The
            // expiry task can never evict a file while this read is active.
            lock (_fileGates.GetOrAdd(id, _ => new object()))
            {
                string local = EnsureExecutable(entry, id);
                byte[] output = new byte[count];
                using var file = new FileStream(local, FileMode.Open,
                    FileAccess.Read, FileShare.Read, 65536, FileOptions.RandomAccess);
                file.Seek((long)offset, SeekOrigin.Begin);
                int n = file.Read(output, 0, output.Length);
                _cacheAccess[id] = DateTime.UtcNow;
                return n == output.Length ? output : output[..n];
            }
        }
        using var request = new HttpRequestMessage(HttpMethod.Get, RemoteUrl(entry.Path));
        AddStrongEtagPrecondition(request, entry);
        request.Headers.Range = new RangeHeaderValue((long)offset,
            (long)offset + count - 1);
        using var response = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (response.StatusCode != HttpStatusCode.PartialContent &&
            response.StatusCode != HttpStatusCode.OK)
            throw new IOException("WebDAV GET HTTP " + (int)response.StatusCode);
        using var stream = response.Content.ReadAsStream();
        if (response.StatusCode == HttpStatusCode.OK && offset != 0)
        {
            long skip = (long)offset;
            byte[] discard = new byte[65536];
            while (skip > 0)
            {
                int n = stream.Read(discard, 0, (int)Math.Min(skip, discard.Length));
                if (n <= 0) return [];
                skip -= n;
            }
        }
        byte[] buffer = new byte[count];
        int read = 0;
        while (read < count)
        {
            int n = stream.Read(buffer, read, count - read);
            if (n <= 0) break;
            read += n;
        }
        return read == count ? buffer : buffer[..read];
    }

    private static string CacheId(Entry entry) => Convert.ToHexString(SHA256.HashData(
            Encoding.UTF8.GetBytes(entry.Path.ToUpperInvariant() + "|" +
                entry.Size + "|" + entry.ModifiedUtc.Ticks + "|" + entry.ETag)));

    private string EnsureExecutable(Entry entry, string id)
    {
        if (_cachedExecutables.TryGetValue(id, out var ready) && File.Exists(ready))
            return ready;
        lock (_fileGates.GetOrAdd(id, _ => new object()))
        {
            if (_cachedExecutables.TryGetValue(id, out ready) && File.Exists(ready))
                return ready;
            string stage = Path.Combine(_localCache, id + ".partial");
            string target = Path.Combine(_localCache, id + ".data");
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get,
                    RemoteUrl(entry.Path));
                AddStrongEtagPrecondition(request, entry);
                using var response = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                response.EnsureSuccessStatusCode();
                using (var source = response.Content.ReadAsStream())
                using (var file = new FileStream(stage, FileMode.Create,
                    FileAccess.Write, FileShare.None)) source.CopyTo(file);
                if (new FileInfo(stage).Length != entry.Size)
                    throw new IOException("Executable changed during download");
                File.Move(stage, target, true);
                _cachedExecutables[id] = target;
                _cacheAccess[id] = DateTime.UtcNow;
                return target;
            }
            finally { try { File.Delete(stage); } catch (IOException) { } }
        }
    }

    private void PruneReadCache(DateTime? testNow = null)
    {
        if (Volatile.Read(ref _disposed) != 0 ||
            Interlocked.Exchange(ref _cachePruning, 1) != 0) return;
        try
        {
            var now = testNow ?? DateTime.UtcNow;
            var items = _cachedExecutables.ToArray().Select(kv =>
            {
                long size = 0;
                try { size = new FileInfo(kv.Value).Length; } catch { }
                return (Id: kv.Key, Path: kv.Value, Size: size,
                    Access: _cacheAccess.GetValueOrDefault(kv.Key, now));
            }).OrderBy(x => x.Access).ToArray();
            long total = items.Sum(x => x.Size);
            foreach (var item in items)
            {
                if (total <= _cacheLimit && now - item.Access < _cacheAge) continue;
                object gate = _fileGates.GetOrAdd(item.Id, _ => new object());
                if (!Monitor.TryEnter(gate)) continue;
                try
                {
                    if (!_cachedExecutables.TryGetValue(item.Id, out var current) ||
                        current != item.Path ||
                        _cacheAccess.GetValueOrDefault(item.Id, now) > item.Access) continue;
                    if (!LocalCachePolicy.PlainPath(current)) continue;
                    File.Delete(current);
                    _cachedExecutables.TryRemove(item.Id, out _);
                    _cacheAccess.TryRemove(item.Id, out _);
                    total -= item.Size;
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                finally { Monitor.Exit(gate); }
            }
        }
        catch { /* The read cache is disposable, never the write journal. */ }
        finally { Interlocked.Exchange(ref _cachePruning, 0); }
    }

    private static void AddStrongEtagPrecondition(HttpRequestMessage request,
        Entry entry)
    {
        // Refuse to silently cache a newer, different revision under the
        // metadata of a file that was replaced during icon/PE extraction.
        if (entry.ETag is { Length: > 1 } etag &&
            etag.StartsWith('"') && etag.EndsWith('"'))
            request.Headers.TryAddWithoutValidation("If-Match", etag);
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        using var drained = new ManualResetEvent(false);
        _cacheTimer.Dispose(drained);
        drained.WaitOne();
        _http.Dispose();
        try { Directory.Delete(_localCache, true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static void PolicyTest()
    {
        using (var cache = new DirectWebDavReadOnlyBackend(new Uri("http://127.0.0.1:9/")))
        {
            var now = DateTime.UtcNow;
            string expired = Path.Combine(cache._localCache, "expired.data");
            string fresh = Path.Combine(cache._localCache, "fresh.data");
            File.WriteAllText(expired, "OLD"); File.WriteAllText(fresh, "NEW");
            cache._cachedExecutables["expired"] = expired;
            cache._cachedExecutables["fresh"] = fresh;
            cache._cacheAccess["expired"] = now.AddMinutes(-31);
            cache._cacheAccess["fresh"] = now.AddMinutes(-1);
            cache.PruneReadCache(now);
            if (File.Exists(expired) || !File.Exists(fresh))
                throw new IOException("Native read-cache expiry mismatch");
            Console.WriteLine("DIRECT_WEBDAV_READ_CACHE_30_MINUTE_EXPIRY_PASS");
        }
        if (Normalize(@"\apps\Downloader.EXE") != "/apps/Downloader.EXE" ||
            Normalize("/") != "/")
            throw new InvalidOperationException("Direct WebDAV path translation failed");
        foreach (string unsafePath in new[] { "../wrong", "folder/../wrong", "C:\\wrong" })
        {
            try { Normalize(unsafePath); throw new InvalidOperationException("Traversal accepted"); }
            catch (ArgumentException) { }
        }
        Console.WriteLine("DIRECT_WEBDAV_ONE_WINFSP_READONLY_PATH_POLICY_PASS");
        const string realQuota =
            "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:propstat>" +
            "<d:prop><d:quota-used-bytes>68000000000</d:quota-used-bytes>" +
            "<d:quota-available-bytes>4932000000000</d:quota-available-bytes>" +
            "</d:prop><d:status>HTTP/1.1 200 OK</d:status>" +
            "</d:propstat></d:response></d:multistatus>";
        var parsed = ParseStorageQuota(realQuota);
        if (parsed?.TotalBytes != 5_000_000_000_000L ||
            parsed.FreeBytes != 4_932_000_000_000L)
            throw new IOException("RFC4331 WebDAV quota was not parsed correctly");
        string absent = realQuota.Replace("HTTP/1.1 200 OK", "HTTP/1.1 404 Not Found");
        if (ParseStorageQuota(absent) is not null ||
            ParseStorageQuota(realQuota.Replace("4932000000000", "-1")) is not null ||
            ParseStorageQuota(realQuota.Replace("4932000000000", "9223372036854775807")) is not null)
            throw new IOException("Absent or invalid WebDAV quota was mistaken for real capacity");
        Console.WriteLine("DIRECT_WEBDAV_RFC4331_QUOTA_PARSER_POLICY_PASS");
    }
}
