using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using System.Xml.Linq;
using Fsp;
using Fsp.Interop;

namespace NASMount.LockBridge;

// WebDAV has no rclone polling backend. Detect changes only in recently
// enumerated folders and notify Windows via WinFsp NotifyBegin/Notify/NotifyEnd.
// No file bytes are downloaded or uploaded by this monitor.
internal sealed class RemoteDirectoryChangeMonitor : IDisposable
{
    private sealed class Watch
    {
        internal DateTime SeenUtc;
        internal DateTime NextUtc;
        internal Dictionary<string, string>? Previous;
    }

    private readonly HttpClient _webdav;
    private readonly HttpClient _proofHttp;
    private readonly Uri _remoteRoot;
    private readonly string _backendRoot;
    private readonly string _rcRoot;
    private readonly string _rcBasic;
    private readonly int _rcPort;
    private readonly string _rcUser;
    private readonly string _rcPassword;
    private readonly FileSystemHost _host;
    private readonly Dictionary<string, Watch> _watched =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stop = new();
    private readonly Task _worker;
    private readonly LocalWorkbookSaveNotificationGuard _localSaves = new();
    // SHA-256 proof is bounded to avoid downloading enormous Office files.
    private const long MaxProofBytes = 128L * 1024 * 1024;
    private static readonly TimeSpan Interval = TimeSpan.FromSeconds(2);
    private const int MaxFolders = 8;
    private const int MaxEntries = 2000;
    private static bool Trace => Environment.GetEnvironmentVariable("NASMOUNT_FSP_TRACE") == "1";

    internal RemoteDirectoryChangeMonitor(FileSystemHost host, NasProfile profile,
        string password, string lowerRoot, int rcPort, string rcUser, string rcPass)
    {
        _host = host;
        _backendRoot = Path.GetFullPath(lowerRoot).TrimEnd('\\') + "\\";
        _remoteRoot = ExcelAtomicBridge.ProfileRoot(profile);
        _rcRoot = $"http://127.0.0.1:{rcPort}";
        _rcBasic = Convert.ToBase64String(Encoding.UTF8.GetBytes(rcUser + ":" + rcPass));
        _rcPort = rcPort;
        _rcUser = rcUser;
        _rcPassword = rcPass;
        _webdav = new HttpClient { Timeout = TimeSpan.FromSeconds(6) };
        _proofHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(24) };
        if (!profile.IsAnonymous)
        {
            var auth = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(
                Encoding.UTF8.GetBytes(profile.Username + ":" + password)));
            _webdav.DefaultRequestHeaders.Authorization = auth;
            _proofHttp.DefaultRequestHeaders.Authorization = auth;
        }
        _worker = Task.Run(PollAsync);
    }

    /// <summary>
    /// Called only after Excel's staged document was safely renamed and its
    /// local SHA-256 checked. Later we verify the remote revision's bytes
    /// before suppressing a self-generated filesystem change notification.
    /// </summary>
    internal void RecordLocalWorkbookSave(string name,
        WorkbookPayloadGuard.Payload payload)
    {
        _localSaves.Record(name, payload.Length, payload.Hash);
        if (Trace) Console.Error.WriteLine("DIRWATCH OWN_SAVE_RECORDED " +
            name + " bytes=" + payload.Length);
    }

    internal void ObserveDirectory(string lowerDirectory)
    {
        string full;
        try { full = Path.GetFullPath(lowerDirectory).TrimEnd('\\') + "\\"; }
        catch { return; }
        if (!full.StartsWith(_backendRoot, StringComparison.OrdinalIgnoreCase) &&
            !full.TrimEnd('\\').Equals(_backendRoot.TrimEnd('\\'),
                StringComparison.OrdinalIgnoreCase)) return;
        string relative = full.Length <= _backendRoot.Length ? "" :
            full[_backendRoot.Length..].Replace('\\', '/').TrimEnd('/');
        if (relative.Contains("..", StringComparison.Ordinal) ||
            relative.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase)) return;
        lock (_gate)
        {
            if (!_watched.TryGetValue(relative, out var watch))
            {
                if (_watched.Count >= MaxFolders)
                    _watched.Remove(_watched.MinBy(x => x.Value.SeenUtc).Key);
                watch = new Watch();
                _watched[relative] = watch;
                if (Trace) Console.Error.WriteLine("DIRWATCH OBSERVE " + relative);
            }
            watch.SeenUtc = DateTime.UtcNow;
        }
    }

    private async Task PollAsync()
    {
        try
        {
            while (!_stop.IsCancellationRequested)
            {
                await Task.Delay(650, _stop.Token);
                List<(string Path, Watch Watch)> due = [];
                lock (_gate)
                {
                    DateTime now = DateTime.UtcNow;
                    foreach (var (name, watch) in _watched.ToArray())
                    {
                        if (now - watch.SeenUtc > TimeSpan.FromMinutes(60))
                        {
                            _watched.Remove(name);
                            continue;
                        }
                        if (now < watch.NextUtc) continue;
                        watch.NextUtc = now + Interval;
                        due.Add((name, watch));
                    }
                }
                foreach (var (name, watch) in due)
                {
                    if (_stop.IsCancellationRequested) break;
                    try
                    {
                        var current = await ReadRemoteAsync(name, _stop.Token);
                        if (current is null) continue;
                        if (Trace) Console.Error.WriteLine("DIRWATCH POLL " +
                            name + " count=" + current.Count + " files=" +
                            string.Join(",", current.Keys.Take(8)));
                        var previous = watch.Previous;
                        if (previous is null)
                        {
                            watch.Previous = current;
                            if (Trace) Console.Error.WriteLine("DIRWATCH BASELINE " +
                                name + " count=" + current.Count + " files=" +
                                string.Join(",", current.Keys.Take(8)));
                            continue;
                        }
                        var changes = Diff(name, previous, current);
                        if (changes.Length == 0)
                        {
                            watch.Previous = current;
                            continue;
                        }
                        // Never cause a remote re-scan while the local VFS has
                        // unsent Office data. The old snapshot stays intact
                        // and the event is retried once the upload drains.
                        if (!BridgeUploadGate.Check(_rcPort, _rcUser, _rcPassword))
                            continue;
                        var external = new List<NotifyInfo>(changes.Length);
                        foreach (var change in changes)
                        {
                            string key = change.FileName.TrimStart('\\')
                                .Replace('\\', '/');
                            string basename = Path.GetFileName(key);
                            if (change.Action == NotifyAction.Modified &&
                                previous.TryGetValue(basename, out var oldVersion) &&
                                current.TryGetValue(basename, out var newVersion) &&
                                _localSaves.MayBeOurSave(key, oldVersion, newVersion,
                                    out var receipt) &&
                                await RemoteMatchesLocalSaveAsync(key, receipt, _stop.Token) &&
                                _localSaves.Acknowledge(key, receipt))
                            {
                                AppStore.Log("REMOTE_DIR_LOCAL_EXCEL_SAVE_ACK " +
                                    key + " bytes=" + receipt.Length);
                                if (Trace) Console.Error.WriteLine(
                                    "DIRWATCH OWN_SAVE_RECONCILED " + key);
                                continue;
                            }
                            external.Add(change);
                        }
                        if (external.Count > 0)
                        {
                            if (Trace) Console.Error.WriteLine("DIRWATCH CHANGE " +
                                name + " changes=" + string.Join(",", external.Select(x =>
                                    x.Action + ":" + x.FileName)));
                            await RefreshRcloneAsync(name, _stop.Token);
                            Notify(external.ToArray());
                        }
                        watch.Previous = current;
                    }
                    catch (OperationCanceledException) when (_stop.IsCancellationRequested)
                    { return; }
                    catch (Exception ex)
                    {
                        if (Trace) Console.Error.WriteLine("DIRWATCH ERROR " + name + " " + ex);
                        // Network errors never produce synthetic mass deletions.
                        AppStore.Log("REMOTE_DIR_POLL_RETRY " + name + " " +
                            ex.GetType().Name + " " + ex.Message);
                    }
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private Uri DirectoryUri(string relative)
    {
        if (relative.Length == 0) return _remoteRoot;
        return new Uri(_remoteRoot, string.Join("/", relative.Split('/')
            .Select(Uri.EscapeDataString)) + "/");
    }

    private async Task<bool> RemoteMatchesLocalSaveAsync(string relative,
        LocalWorkbookSaveNotificationGuard.Receipt receipt,
        CancellationToken token)
    {
        if (receipt.Length > MaxProofBytes) return false;
        try
        {
            Uri file = new(_remoteRoot, string.Join("/", relative.Split('/')
                .Select(Uri.EscapeDataString)));
            using var request = new HttpRequestMessage(HttpMethod.Get, file);
            using var response = await _proofHttp.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, token);
            if (response.StatusCode != HttpStatusCode.OK ||
                (response.Content.Headers.ContentLength is long size &&
                 size != receipt.Length))
                return false;
            using var sha = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            await using var body = await response.Content.ReadAsStreamAsync(token);
            byte[] block = new byte[128 * 1024];
            long total = 0;
            while (true)
            {
                int bytes = await body.ReadAsync(block.AsMemory(), token);
                if (bytes == 0) break;
                total += bytes;
                if (total > receipt.Length || total > MaxProofBytes) return false;
                sha.AppendData(block.AsSpan(0, bytes));
            }
            string remoteHash = Convert.ToHexString(sha.GetHashAndReset());
            bool matched = total == receipt.Length &&
                remoteHash.Equals(receipt.Sha256, StringComparison.OrdinalIgnoreCase);
            if (!matched && Trace)
                Console.Error.WriteLine("DIRWATCH REMOTE_MISMATCH " + relative +
                    " expectedBytes=" + receipt.Length + " actualBytes=" + total);
            return matched;
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            // Fail closed: a failed proof must not mask a real remote edit.
            AppStore.Log("REMOTE_DIR_LOCAL_SAVE_PROOF_FAILED " + relative +
                " " + ex.GetType().Name);
            return false;
        }
    }

    private async Task<Dictionary<string, string>?> ReadRemoteAsync(
        string relative, CancellationToken token)
    {
        Uri uri = DirectoryUri(relative);
        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), uri);
        request.Headers.TryAddWithoutValidation("Depth", "1");
        request.Content = new StringContent(
            "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
            "<d:getetag/><d:getcontentlength/><d:getlastmodified/><d:resourcetype/>" +
            "</d:prop></d:propfind>", Encoding.UTF8, "application/xml");
        using var response = await _webdav.SendAsync(request, token);
        if (response.StatusCode != HttpStatusCode.MultiStatus)
            throw new IOException("WebDAV PROPFIND HTTP " + (int)response.StatusCode);
        byte[] data = await response.Content.ReadAsByteArrayAsync(token);
        if (data.Length > 5 * 1024 * 1024) return null;
        using var reader = XmlReader.Create(new MemoryStream(data), new XmlReaderSettings {
            XmlResolver = null, DtdProcessing = DtdProcessing.Prohibit
        });
        var document = XDocument.Load(reader);
        XNamespace dav = "DAV:";
        string parent = Uri.UnescapeDataString(uri.AbsolutePath).TrimEnd('/');
        var list = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in document.Descendants(dav + "response"))
        {
            string? href = item.Element(dav + "href")?.Value;
            if (string.IsNullOrWhiteSpace(href)) continue;
            Uri child;
            try { child = new Uri(uri, href); } catch { continue; }
            if (child.Host != uri.Host || child.Port != uri.Port) continue;
            string path = Uri.UnescapeDataString(child.AbsolutePath).TrimEnd('/');
            string directory = path.Contains('/') ? path[..path.LastIndexOf('/')] : "";
            if (directory != parent) continue;
            string name = path[(path.LastIndexOf('/') + 1)..];
            if (name.Length == 0 ||
                name.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) ||
                name.StartsWith(".nasmount-stage-", StringComparison.OrdinalIgnoreCase))
                continue;
            var prop = item.Descendants(dav + "prop").FirstOrDefault();
            bool isFolder = prop?.Element(dav + "resourcetype")?
                .Element(dav + "collection") is not null;
            list[name] = (isFolder ? "D|" : "F|") +
                (prop?.Element(dav + "getetag")?.Value ?? "") + "|" +
                (prop?.Element(dav + "getcontentlength")?.Value ?? "") + "|" +
                (prop?.Element(dav + "getlastmodified")?.Value ?? "");
            if (list.Count > MaxEntries) return null;
        }
        return list;
    }

    internal static NotifyInfo[] Diff(string directory,
        IReadOnlyDictionary<string, string> before,
        IReadOnlyDictionary<string, string> after)
    {
        var events = new List<NotifyInfo>();
        string prefix = "\\" + directory.Replace('/', '\\').Trim('\\');
        if (prefix != "\\") prefix += "\\";
        foreach (var (name, value) in before)
        {
            bool still = after.TryGetValue(name, out var next);
            if (still && next == value) continue;
            events.Add(new NotifyInfo {
                FileName = prefix + name,
                Action = still ? NotifyAction.Modified : NotifyAction.Removed,
                Filter = still ? NotifyFilter.ChangeLastWrite :
                    value.StartsWith("D|", StringComparison.Ordinal)
                        ? NotifyFilter.ChangeDirName : NotifyFilter.ChangeFileName
            });
        }
        foreach (var (name, value) in after)
            if (!before.ContainsKey(name))
                events.Add(new NotifyInfo { FileName = prefix + name,
                    Action = NotifyAction.Added, Filter = value.StartsWith("D|",
                        StringComparison.Ordinal)
                        ? NotifyFilter.ChangeDirName : NotifyFilter.ChangeFileName });
        return events.Count <= 160 ? events.ToArray() : [];
    }

    private async Task RefreshRcloneAsync(string relative, CancellationToken token)
    {
        try
        {
            using var rc = new HttpClient { Timeout = TimeSpan.FromSeconds(4) };
            rc.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Basic", _rcBasic);
            // Root refresh is {}. Passing dir="" makes rclone 1.75
            // return HTTP 500 instead of refreshing the root.
            // rclone RC parameters are string-valued; sending JSON boolean
            // recursive=false causes HTTP 500 ("value must be string").
            // Default is non-recursive, so omit recursive completely.
            string body = relative.Length == 0 ? "{}" :
                System.Text.Json.JsonSerializer.Serialize(new { dir = relative });
            using var reply = await rc.PostAsync(_rcRoot + "/vfs/refresh",
                new StringContent(body, Encoding.UTF8, "application/json"), token);
            if (Trace) Console.Error.WriteLine("DIRWATCH RC_REFRESH " +
                relative + " HTTP=" + (int)reply.StatusCode);
            if (!reply.IsSuccessStatusCode)
            {
                string problem = await reply.Content.ReadAsStringAsync(token);
                if (Trace) Console.Error.WriteLine("DIRWATCH RC_ERROR_BODY " +
                    problem[..Math.Min(problem.Length, 250)]);
                AppStore.Log("REMOTE_DIR_RC_REFRESH_WARNING " + relative +
                    " HTTP " + (int)reply.StatusCode);
            }
        }
        catch (Exception ex)
        {
            AppStore.Log("REMOTE_DIR_RC_REFRESH_WARNING " + relative + " " + ex.Message);
        }
    }

    private void Notify(NotifyInfo[] events)
    {
        try
        {
            // WinFsp is now mounted with CaseSensitiveSearch=true to keep
            // Excel's original filename spelling on first Open. In that
            // mode notifications must use the ACTUAL stored spelling, not
            // uppercase paths. The WebDAV PROPFIND snapshot contains exact
            // names, including nested directory names.
            // Native WinFsp notifications prompt Explorer/ReadDirectory
            // to update without F5 or restarting the mount.
            int start = _host.NotifyBegin(0);
            if (Trace) Console.Error.WriteLine("DIRWATCH NOTIFY_BEGIN=" + start);
            if (start < 0) return;
            try
            {
                int sent = _host.Notify(events);
                if (Trace) Console.Error.WriteLine("DIRWATCH NOTIFY_SEND=" + sent);
            }
            finally { _host.NotifyEnd(); }
            AppStore.Log("REMOTE_DIR_AUTO_REFRESH count=" + events.Length +
                " " + string.Join(",", events.Take(5).Select(x =>
                    x.Action + ":" + x.FileName)));
        }
        catch (Exception ex)
        {
            AppStore.Log("REMOTE_DIR_NOTIFY_WARNING " + ex.Message);
        }
    }

    public void Dispose()
    {
        _stop.Cancel();
        try { _worker.Wait(TimeSpan.FromSeconds(9)); } catch { }
        _webdav.Dispose();
        _proofHttp.Dispose();
        _stop.Dispose();
    }
}
