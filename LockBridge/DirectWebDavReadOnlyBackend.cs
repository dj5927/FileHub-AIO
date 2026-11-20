using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
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

    private sealed record TimedEntry(Entry Value, DateTime ExpiresUtc);
    private sealed record TimedDirectory(Entry[] Values, DateTime ExpiresUtc);
    private readonly Uri _root;
    private readonly HttpClient _http;
    private readonly string _localCache;
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

    // Only a persisted, user-configured WebDAV profile may authorize a
    // non-loopback host. The command-line test URI never gains this ability.
    internal DirectWebDavReadOnlyBackend(NasProfile profile, string password)
        : this(ExcelAtomicBridge.ProfileRoot(profile), profile, password,
            requireLoopback: false)
    {
    }

    private DirectWebDavReadOnlyBackend(Uri root, NasProfile? profile,
        string password, bool requireLoopback)
    {
        if ((requireLoopback && (!root.IsLoopback || root.Scheme != Uri.UriSchemeHttp)) ||
            root.UserInfo.Length > 0 || root.Query.Length > 0 ||
            root.Fragment.Length > 0 ||
            (profile is not null && (profile.Protocol != "WebDAV" ||
                profile.Host.Length == 0 ||
                (profile.IsAnonymous == false && password.Length == 0))))
            throw new ArgumentException("Unsafe direct WebDAV endpoint or credentials");
        _root = new Uri(root.AbsoluteUri.TrimEnd('/') + "/");
        _http = profile is not null
            ? WebDavTlsPolicy.CreateClient(profile, TimeSpan.FromSeconds(25),
                allowRedirect: false)
            : new HttpClient(new HttpClientHandler {
                AllowAutoRedirect = false,
                UseCookies = false,
                AutomaticDecompression = DecompressionMethods.None
            }) { Timeout = TimeSpan.FromSeconds(25) };
        if (profile is { IsAnonymous: false })
            _http.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(
                        profile.Username + ":" + password)));
        _localCache = Path.Combine(Path.GetTempPath(),
            "FileHubDirectWebDavLab_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_localCache);
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
            string? etag = prop.Element(dav + "getetag")?.Value;
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
        if (result is not null)
        {
            _missing.TryRemove(p, out _);
            _metadata[p] = new TimedEntry(result, DateTime.UtcNow + MetadataTtl);
        }
        else _missing[p] = DateTime.UtcNow + TimeSpan.FromSeconds(5);
        return result;
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
            string local = EnsureExecutable(entry);
            byte[] output = new byte[count];
            using var file = new FileStream(local, FileMode.Open,
                FileAccess.Read, FileShare.Read, 65536, FileOptions.RandomAccess);
            file.Seek((long)offset, SeekOrigin.Begin);
            int n = file.Read(output, 0, output.Length);
            return n == output.Length ? output : output[..n];
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

    private string EnsureExecutable(Entry entry)
    {
        string id = Convert.ToHexString(SHA256.HashData(
            Encoding.UTF8.GetBytes(entry.Path.ToUpperInvariant() + "|" +
                entry.Size + "|" + entry.ModifiedUtc.Ticks + "|" + entry.ETag)));
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
                return target;
            }
            finally { try { File.Delete(stage); } catch (IOException) { } }
        }
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
        _http.Dispose();
        try { Directory.Delete(_localCache, true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static void PolicyTest()
    {
        if (Normalize(@"\apps\Downloader.EXE") != "/apps/Downloader.EXE" ||
            Normalize("/") != "/")
            throw new InvalidOperationException("Direct WebDAV path translation failed");
        foreach (string unsafePath in new[] { "../wrong", "folder/../wrong", "C:\\wrong" })
        {
            try { Normalize(unsafePath); throw new InvalidOperationException("Traversal accepted"); }
            catch (ArgumentException) { }
        }
        Console.WriteLine("DIRECT_WEBDAV_ONE_WINFSP_READONLY_PATH_POLICY_PASS");
    }
}
