using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;

namespace NASMount;

/// <summary>
/// Per-profile loopback WebDAV forwarder used ONLY in opt-in native-lock mode.
/// rclone's actual PUT/MOVE/DELETE calls flow through this gateway, where a
/// server-issued RFC4918 lock token can be inserted for the exact resource.
///
/// A lock is acquired by WinFsp LockBridge via an authenticated control call.
/// No file-name sidecars or remote directories are created by this component.
/// The upstream endpoint and credentials stay in the GUI process.
/// </summary>
internal sealed class NativeWebDavGateway : IDisposable
{
    private static readonly XNamespace Dav = "DAV:";
    private readonly HttpListener listener = new();
    private readonly HttpClient upstream;
    private readonly Uri remoteRoot;
    private readonly string nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(18));
    private readonly string controlSecret =
        Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
    private readonly string shareRoot;
    private readonly ConcurrentDictionary<string, OwnedLock> leases =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly SemaphoreSlim leaseGate = new(1, 1);
    private readonly CancellationTokenSource stopping = new();
    private readonly Task acceptTask;
    private readonly Task renewTask;
    private bool disposed;

    private sealed class OwnedLock
    {
        internal required string Id;
        internal required NativeWebDavLockProtocol.Lease Lease;
        internal bool Healthy = true;
    }

    internal string Url { get; }
    internal string ControlUrl => Url + "__native_lock_control/";
    internal string ControlSecret => controlSecret;
    internal int ActiveLeases => leases.Count;

    private NativeWebDavGateway(Uri remote, string share,
        HttpMessageHandler? fakeUpstream, string? user, string? password)
    {
        if (remote.Scheme is not ("http" or "https") ||
            !remote.IsAbsoluteUri || remote.UserInfo.Length > 0 ||
            remote.AbsolutePath != "/")
            throw new ArgumentException("Expected an HTTP(S) WebDAV server root");
        remoteRoot = remote;
        shareRoot = share.Replace('\\', '/').Trim('/');
        var handler = fakeUpstream ??
            WebDavTlsPolicy.CreateHandler(new NasProfile {
                Protocol = "WebDAV", Host = remote.Host, Port = remote.Port,
                WebDavUseHttp = remote.Scheme == "http"
            }, allowRedirect: false);
        upstream = new HttpClient(handler, disposeHandler: true)
            { Timeout = Timeout.InfiniteTimeSpan };
        if (!string.IsNullOrEmpty(user))
            upstream.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(
                        user + ":" + (password ?? ""))));
        int port = 0;
        for (int tryPort = 0; tryPort < 20; tryPort++)
        {
            int candidate = RandomNumberGenerator.GetInt32(23000, 61000);
            string prefix = "http://127.0.0.1:" + candidate + "/";
            listener.Prefixes.Clear();
            listener.Prefixes.Add(prefix);
            try { listener.Start(); port = candidate; break; }
            catch (HttpListenerException) { }
        }
        if (port == 0) throw new IOException("No free HTTP.sys loopback port");
        Url = "http://127.0.0.1:" + port + "/" + nonce + "/";
        acceptTask = Task.Run(AcceptAsync);
        renewTask = Task.Run(RenewAsync);
    }

    internal static NativeWebDavGateway Start(NasProfile profile, string? password) {
        if (profile.Protocol != "WebDAV" || !profile.UseLockBridge ||
            !profile.UseNativeWebDavLocks)
            throw new InvalidOperationException("Native lock gateway is disabled for this profile");
        int port = profile.Port > 0 ? profile.Port : 5006;
        Uri remote = new((profile.WebDavUseHttp ? "http://" : "https://") +
            profile.Host + ":" + port + "/");
        return new NativeWebDavGateway(remote, profile.Share,
            null, profile.IsAnonymous ? null : profile.Username, password);
    }

    internal static NativeWebDavGateway StartLab(
        Uri serverRoot, string share, HttpMessageHandler fakeUpstream) =>
        new(serverRoot, share, fakeUpstream, null, null);

    private async Task AcceptAsync()
    {
        while (!stopping.IsCancellationRequested)
        {
            HttpListenerContext context;
            try { context = await listener.GetContextAsync().WaitAsync(stopping.Token); }
            catch (Exception e) when (e is HttpListenerException or
                OperationCanceledException or ObjectDisposedException) { break; }
            _ = Task.Run(() => ServeAsync(context));
        }
    }

    private async Task ServeAsync(HttpListenerContext context)
    {
        try
        {
            HttpListenerRequest request = context.Request;
            if (!request.IsLocal ||
                !request.Url!.AbsolutePath.StartsWith("/" + nonce + "/",
                    StringComparison.Ordinal))
            {
                context.Response.StatusCode = 404;
                return;
            }
            string relative = request.Url.AbsolutePath[("/" + nonce + "/").Length..];
            if (!SafeRelativePath(relative))
            {
                context.Response.StatusCode = 400;
                return;
            }
            if (relative.StartsWith("__native_lock_control/", StringComparison.Ordinal))
            {
                await ControlAsync(context, relative);
                return;
            }
            await ForwardAsync(context, relative);
        }
        catch (Exception ex)
        {
            AppStore.Log("NATIVE_DAV_PROXY_REQUEST_FAILED " + ex.GetType().Name +
                " " + ex.Message);
            try { context.Response.StatusCode = 502; } catch { }
        }
        finally
        {
            try { context.Response.Close(); } catch { }
        }
    }

    private static bool SafeRelativePath(string path)
    {
        if (path.Length > 4096 || path.Contains('\\') ||
            path.IndexOfAny(['\r','\n','\0']) >= 0) return false;
        foreach (string piece in path.Split('/'))
        {
            if (piece == "") continue;
            string unescaped;
            try { unescaped = Uri.UnescapeDataString(piece); }
            catch { return false; }
            if (unescaped is "." or ".." || unescaped.Contains('/') ||
                unescaped.Contains('\\') || unescaped.IndexOfAny(['\r','\n','\0']) >= 0)
                return false;
        }
        return true;
    }

    private Uri Remote(string relative)
    {
        if (!SafeRelativePath(relative)) throw new IOException("Unsafe WebDAV URL");
        return new Uri(remoteRoot, relative);
    }

    private string ClientKey(string relative)
    {
        string normalized = relative.TrimStart('/');
        if (shareRoot.Length > 0)
            normalized = shareRoot + "/" + normalized;
        // WebDAV paths use percent encoding for non-ASCII and spaces.
        return string.Join("/", normalized.Split('/')
            .Select(segment => Uri.EscapeDataString(segment)));
    }

    private static bool Office(string key)
    {
        string name = Uri.UnescapeDataString(key.Split('/').Last());
        if (name.StartsWith("~$", StringComparison.OrdinalIgnoreCase))
            return false;
        return Path.GetExtension(name).ToLowerInvariant() is
            ".xlsx" or ".xlsm" or ".xlsb" or ".xls" or
            ".docx" or ".docm" or ".doc" or ".pptx" or ".pptm" or ".ppt" or
            ".odt" or ".ods" or ".odp" or ".hwp" or ".hwpx";
    }

    private async Task ControlAsync(HttpListenerContext context, string path)
    {
        var request = context.Request;
        if (request.HttpMethod != "POST" ||
            request.Headers["X-NASMount-Control"] != controlSecret ||
            request.ContentLength64 is < 1 or > 8192)
        {
            context.Response.StatusCode = 403;
            return;
        }
        using var buffer = new MemoryStream();
        await request.InputStream.CopyToAsync(buffer, stopping.Token);
        if (buffer.Length > 8192) { context.Response.StatusCode = 413; return; }
        ControlPayload? payload = JsonSerializer.Deserialize<ControlPayload>(
            buffer.ToArray());
        if (payload is null || !SafeRelativePath(payload.Path))
        {
            context.Response.StatusCode = 400;
            return;
        }
        string key = ClientKey(payload.Path);
        // These control calls run synchronously in WinFsp callbacks through
        // LockBridge. Never let a stalled remote LOCK/UNLOCK wait forever.
        // This deadline does NOT apply to streaming file PUT/GET forwarding.
        using var commandDeadline =
            CancellationTokenSource.CreateLinkedTokenSource(stopping.Token);
        commandDeadline.CancelAfter(TimeSpan.FromSeconds(12));
        await leaseGate.WaitAsync(commandDeadline.Token);
        try
        {
            if (path == "__native_lock_control/acquire")
            {
                if (leases.ContainsKey(key))
                    throw new IOException("Local lock already active");
                // Old client compatibility: NEVER take a native-only lock if
                // the old marker is present; otherwise versions fail to see
                // one another's edit ownership.
                using (var legacyCheck = await upstream.SendAsync(
                    new HttpRequestMessage(HttpMethod.Head, Remote(key + ".nasmount.lock")),
                    commandDeadline.Token))
                {
                    if (legacyCheck.StatusCode != HttpStatusCode.NotFound)
                        throw new IOException("Old .nasmount.lock marker exists or cannot be checked");
                }
                var lease = await NativeWebDavLockProtocol.AcquireAsync(
                    upstream, Remote(key), commandDeadline.Token);
                string id = Guid.NewGuid().ToString("N");
                if (!leases.TryAdd(key, new OwnedLock { Id = id, Lease = lease }))
                {
                    using var rollbackDeadline = new CancellationTokenSource(
                        TimeSpan.FromSeconds(6));
                    await NativeWebDavLockProtocol.ReleaseAsync(
                        upstream, lease, rollbackDeadline.Token);
                    throw new IOException("Concurrent local lease conflict");
                }
                await ReplyJson(context, 200, new ControlAnswer(id, lease.ExpiresUtc, true));
                return;
            }
            if (!leases.TryGetValue(key, out var active) || active.Id != payload.Id)
                throw new IOException("Missing/mismatched native lock owner");
            if (path == "__native_lock_control/release")
            {
                await NativeWebDavLockProtocol.ReleaseAsync(
                    upstream, active.Lease, commandDeadline.Token);
                leases.TryRemove(key, out _);
                await ReplyJson(context, 200, new ControlAnswer(active.Id, active.Lease.ExpiresUtc, true));
                return;
            }
            if (path == "__native_lock_control/refresh")
            {
                active.Lease = await NativeWebDavLockProtocol.RefreshAsync(
                    upstream, active.Lease, commandDeadline.Token);
                active.Healthy = true;
                await ReplyJson(context, 200,
                    new ControlAnswer(active.Id, active.Lease.ExpiresUtc, true));
                return;
            }
            context.Response.StatusCode = 404;
        }
        catch (Exception ex)
        {
            AppStore.Log("NATIVE_DAV_CONTROL_FAILED " + path + " " + ex.Message);
            await ReplyJson(context, 409,
                new ControlAnswer("", DateTimeOffset.MinValue, false));
        }
        finally { leaseGate.Release(); }
    }

    private sealed record ControlPayload(string Path, string Id);
    private sealed record ControlAnswer(string Id, DateTimeOffset ExpiresUtc, bool Success);

    private static async Task ReplyJson(HttpListenerContext context,
        int status, ControlAnswer answer)
    {
        byte[] json = JsonSerializer.SerializeToUtf8Bytes(answer);
        context.Response.StatusCode = status;
        context.Response.ContentType = "application/json";
        context.Response.ContentLength64 = json.Length;
        await context.Response.OutputStream.WriteAsync(json);
    }

    private async Task ForwardAsync(HttpListenerContext context, string path)
    {
        HttpListenerRequest inbound = context.Request;
        Uri target = Remote(path + inbound.Url!.Query);
        string method = inbound.HttpMethod.ToUpperInvariant();
        if (path.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) ||
            path.Contains(".nasmount.lock/", StringComparison.OrdinalIgnoreCase))
        {
            // Prevent an accidental regression to visible marker folders.
            context.Response.StatusCode = 403;
            return;
        }
        string? destination = inbound.Headers["Destination"];
        Uri? destinationRemote = null;
        if (!string.IsNullOrEmpty(destination))
        {
            if (!Uri.TryCreate(destination, UriKind.Absolute, out var dstUri) ||
                !string.Equals(dstUri.Host, "127.0.0.1",
                    StringComparison.OrdinalIgnoreCase) ||
                dstUri.Port != inbound.Url.Port ||
                !dstUri.AbsolutePath.StartsWith("/" + nonce + "/", StringComparison.Ordinal))
            {
                context.Response.StatusCode = 400;
                return;
            }
            string dstPath = dstUri.AbsolutePath[("/" + nonce + "/").Length..];
            if (!SafeRelativePath(dstPath)) { context.Response.StatusCode = 400; return; }
            destinationRemote = Remote(dstPath);
        }
        // RFC4918: a MOVE of a locked source does not move the write lock.
        // Excel renames the OLD workbook to a backup *.tmp before replacing
        // the original. Treat only that backup rename as COPY so the server
        // original stays protected throughout the transaction. If the
        // server rejects COPY, the original remains intact.
        bool stagedBackup = method == "MOVE" && Office(path) &&
            destinationRemote != null && !Office(destinationRemote.AbsolutePath) &&
            IsOfficeBackup(destinationRemote.AbsolutePath);
        if (method == "MOVE" && Office(path) &&
            destinationRemote != null && !Office(destinationRemote.AbsolutePath) &&
            !stagedBackup)
        {
            context.Response.StatusCode = 409;
            return;
        }
        using var forward = new HttpRequestMessage(
            new HttpMethod(stagedBackup ? "COPY" : method), target);
        foreach (string? key in inbound.Headers.AllKeys)
        {
            if (string.IsNullOrEmpty(key) || key.Equals("Host", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Authorization", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Connection", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Content-Length", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Expect", StringComparison.OrdinalIgnoreCase) ||
                key.Equals("Destination", StringComparison.OrdinalIgnoreCase))
                continue;
            if (!forward.Headers.TryAddWithoutValidation(key, inbound.Headers[key]))
            {
                // Content headers are added after StreamContent is created.
                if (method is "PUT" or "POST" or "PROPPATCH" or "PROPFIND")
                    continue;
            }
        }

        if (destinationRemote != null)
        {
            forward.Headers.TryAddWithoutValidation("Destination",
                destinationRemote.AbsoluteUri);
        }

        // Fail closed for every mutating request that touches an Office file.
        // Rclone's global WebDAV headers are never used; each request gets the
        // exact server-issued token for that resource (or is denied).
        bool mutation = method is "PUT" or "MOVE" or "DELETE" or "COPY" or
            "PROPPATCH" or "PATCH";
        if (method == "DELETE" && Office(path))
        {
            // With a server-native LOCK, deleting the original halfway
            // through an Office save would turn a recoverable rename error
            // into an actual lost workbook. Explicit document deletion needs
            // its own transaction/recovery path; fail closed in this mode.
            context.Response.StatusCode = 423;
            return;
        }
        var tokenClauses = new List<string>();
        if (mutation && Office(path))
        {
            if (!TryActiveToken(path, out string token))
            { context.Response.StatusCode = 423; return; }
            tokenClauses.Add("<" + target.AbsoluteUri + "> (" + token + ")");
        }
        if (mutation && destinationRemote != null && Office(destinationRemote.AbsolutePath))
        {
            string remoteKey = destinationRemote.AbsolutePath.TrimStart('/');
            if (!TryActiveToken(remoteKey, out string token))
            { context.Response.StatusCode = 423; return; }
            tokenClauses.Add("<" + destinationRemote.AbsoluteUri + "> (" + token + ")");
        }
        if (tokenClauses.Count > 0)
        {
            if (inbound.Headers["If"] is { Length: > 0 })
            { context.Response.StatusCode = 412; return; }
            forward.Headers.Remove("If");
            forward.Headers.TryAddWithoutValidation("If",
                string.Join(" ", tokenClauses));
        }

        if (inbound.HasEntityBody)
        {
            forward.Content = new StreamContent(inbound.InputStream);
            if (inbound.ContentLength64 >= 0)
                forward.Content.Headers.ContentLength = inbound.ContentLength64;
            if (!string.IsNullOrEmpty(inbound.ContentType))
                forward.Content.Headers.TryAddWithoutValidation("Content-Type",
                    inbound.ContentType);
        }
        using var result = await upstream.SendAsync(
            forward, HttpCompletionOption.ResponseHeadersRead, stopping.Token);
        context.Response.StatusCode = (int)result.StatusCode;
        if (result.Content.Headers.ContentType is { } contentType)
            context.Response.ContentType = contentType.ToString();
        foreach (var h in result.Headers.Concat(result.Content.Headers))
        {
            if (h.Key is "Transfer-Encoding" or "Connection" or
                "Content-Length" or "Content-Type" or "Date" or "Server" or
                "Keep-Alive") continue;
            try { context.Response.Headers[h.Key] = string.Join(", ", h.Value); }
            catch (ArgumentException) { }
        }
        if (method == "HEAD" || result.StatusCode == HttpStatusCode.NoContent)
        {
            context.Response.ContentLength64 = 0;
            return;
        }
        if (method == "PROPFIND" &&
            result.StatusCode == (HttpStatusCode)207)
        {
            byte[] xml = await result.Content.ReadAsByteArrayAsync(stopping.Token);
            if (xml.Length > 16 * 1024 * 1024)
                throw new IOException("Too large WebDAV PROPFIND response");
            byte[] rewritten = RewriteDavHrefs(xml);
            context.Response.ContentLength64 = rewritten.Length;
            await context.Response.OutputStream.WriteAsync(rewritten, stopping.Token);
            return;
        }
        if (result.Content.Headers.ContentLength is long length)
            context.Response.ContentLength64 = length;
        else
            context.Response.SendChunked = true;
        await using var incoming = await result.Content.ReadAsStreamAsync(stopping.Token);
        await incoming.CopyToAsync(context.Response.OutputStream, stopping.Token);
    }

    private static bool IsOfficeBackup(string destination)
    {
        string name = Uri.UnescapeDataString(destination.Split('/').Last());
        if (name.Length is < 5 or > 255 ||
            name.Contains("..", StringComparison.Ordinal)) return false;
        // Excel's backup may be an eight-hexadecimal name without .tmp on
        // some versions; the earlier gateway rejected that MOVE with 409,
        // making Excel treat its own workbook as modified by someone else.
        return name.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) ||
            System.Text.RegularExpressions.Regex.IsMatch(name,
                @"^[0-9a-fA-F]{8}$");
    }

    private bool TryActiveToken(string remotePath, out string token)
    {
        token = "";
        string key = remotePath.TrimStart('/');
        if (!leases.TryGetValue(key, out var active) || !active.Healthy ||
            active.Lease.ExpiresUtc <= DateTimeOffset.UtcNow.AddSeconds(10))
            return false;
        token = active.Lease.Token;
        return true;
    }

    private byte[] RewriteDavHrefs(byte[] xml)
    {
        using var input = new MemoryStream(xml);
        var settings = new System.Xml.XmlReaderSettings {
            DtdProcessing = System.Xml.DtdProcessing.Prohibit,
            XmlResolver = null, MaxCharactersInDocument = 16 * 1024 * 1024
        };
        using var reader = System.Xml.XmlReader.Create(input, settings);
        var doc = XDocument.Load(reader);
        string remoteAuthority = remoteRoot.GetLeftPart(UriPartial.Authority);
        foreach (var href in doc.Descendants(Dav + "href"))
        {
            string old = href.Value;
            if (old.StartsWith(remoteAuthority, StringComparison.OrdinalIgnoreCase))
                old = new Uri(old).PathAndQuery;
            if (old.StartsWith("/") && !old.StartsWith("//"))
                href.Value = "/" + nonce + old;
        }
        return Encoding.UTF8.GetBytes(doc.ToString(SaveOptions.DisableFormatting));
    }

    private async Task RenewAsync()
    {
        while (!stopping.IsCancellationRequested)
        {
            try { await Task.Delay(TimeSpan.FromSeconds(20), stopping.Token); }
            catch (OperationCanceledException) { break; }
            await leaseGate.WaitAsync(stopping.Token);
            try
            {
                foreach (OwnedLock active in leases.Values)
                {
                    if (active.Lease.ExpiresUtc > DateTimeOffset.UtcNow.AddSeconds(120) &&
                        active.Healthy) continue;
                    try {
                        active.Lease = await NativeWebDavLockProtocol.RefreshAsync(
                            upstream, active.Lease, stopping.Token);
                        active.Healthy = true;
                    }
                    catch (Exception ex) {
                        active.Healthy = false;
                        AppStore.Log("NATIVE_DAV_LEASE_UNSAFE " +
                            ex.GetType().Name + " " + ex.Message);
                    }
                }
            }
            finally { leaseGate.Release(); }
        }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stopping.Cancel();
        listener.Close();
        // The manager calls this only AFTER the lock bridge confirms all
        // handles closed and pending uploads drained; don't force UNLOCK.
        upstream.Dispose();
        leaseGate.Dispose();
        stopping.Dispose();
    }
}
