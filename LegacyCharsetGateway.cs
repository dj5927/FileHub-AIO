using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;

namespace NASMount;

/// <summary>
/// A localhost-only, per-profile WebDAV bridge to byte-accurate legacy
/// FTP/SFTP names. rclone/WinFsp still handles the Windows disk and VFS.
/// LegacyCharsetStorage applies the selected encoding on the remote wire.
/// </summary>
internal sealed class LegacyCharsetGateway : IDisposable
{
    private static readonly XNamespace Dav = "DAV:";
    private readonly ILegacyCharsetStore storage;
    private readonly TcpListener listener;
    private readonly CancellationTokenSource stopping = new();
    private readonly SemaphoreSlim storeGate = new(1, 1);
    private readonly bool allowWrites;
    private readonly string nonce = Convert.ToHexString(RandomNumberGenerator.GetBytes(24));
    private Task? acceptTask;
    private bool disposed;
    internal string Url { get; }

    private LegacyCharsetGateway(ILegacyCharsetStore storage, bool allowWrites)
    {
        this.storage = storage;
        this.allowWrites = allowWrites;
        listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        Url = "http://127.0.0.1:" + port + "/" + nonce + "/";
        acceptTask = Task.Run(AcceptAsync);
    }

    internal static async Task<LegacyCharsetGateway> StartAsync(
        NasProfile profile, string? password)
    {
        if (!CharsetSupport.RequiresGateway(profile))
            throw new InvalidOperationException("UTF-8 connections do not use the gateway");
        string choice = profile.Protocol == "FTP" ? profile.FtpCharset : profile.SftpCharset;
        var encoding = CharsetSupport.GetEncoding(choice) ??
            throw new InvalidOperationException("설정한 문자셋을 사용할 수 없습니다.");
        var store = await Task.Run(() => new LegacyCharsetStorage(profile, password, encoding));
        try
        {
            // Validate a real directory listing before claiming success.
            await Task.Run(() => store.List(""));
            return new LegacyCharsetGateway(store,
                profile.UseLockBridge || profile.AllowUnprotectedWrites);
        }
        catch { store.Dispose(); throw; }
    }

    // In tests, exercise the exact same HTTP surface with a disposable fake
    // server; no user credentials or actual remote data are accessed.
    internal static LegacyCharsetGateway StartLab(
        ILegacyCharsetStore store, bool writable = true) => new(store, writable);

    private async Task AcceptAsync()
    {
        while (!stopping.IsCancellationRequested)
        {
            TcpClient socket;
            try { socket = await listener.AcceptTcpClientAsync(stopping.Token); }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (stopping.IsCancellationRequested) { break; }
            _ = Task.Run(() => ServeAsync(socket));
        }
    }

    private sealed class RequestReader(NetworkStream stream)
    {
        private readonly byte[] data = new byte[32768];
        private int pos, end;
        internal async Task<int> ReadAsync(Memory<byte> destination, CancellationToken cancel)
        {
            if (pos == end)
            {
                pos = 0;
                end = await stream.ReadAsync(data, cancel);
                if (end == 0) return 0;
            }
            int count = Math.Min(destination.Length, end - pos);
            data.AsMemory(pos, count).CopyTo(destination);
            pos += count;
            return count;
        }
        internal async Task<byte> ByteAsync(CancellationToken cancel)
        {
            if (pos == end)
            {
                pos = 0;
                end = await stream.ReadAsync(data, cancel);
                if (end == 0) throw new EndOfStreamException();
            }
            return data[pos++];
        }
        internal async Task<string> LineAsync(CancellationToken cancel)
        {
            using var data = new MemoryStream();
            for (int n = 0; n < 32768; n++)
            {
                byte b = await ByteAsync(cancel);
                if (b == (byte)'\n')
                {
                    byte[] result = data.ToArray();
                    int length = result.Length;
                    if (length > 0 && result[length - 1] == '\r') length--;
                    return Encoding.Latin1.GetString(result, 0, length);
                }
                data.WriteByte(b);
            }
            throw new IOException("HTTP header line too long");
        }
        internal async Task ReadBodyAsync(Stream output, long? length,
            bool chunked, CancellationToken cancel)
        {
            var buffer = new byte[64 * 1024];
            async Task CopyExact(long amount)
            {
                while (amount > 0)
                {
                    int count = await ReadAsync(buffer.AsMemory(0,
                        (int)Math.Min(amount, buffer.Length)), cancel);
                    if (count == 0) throw new EndOfStreamException();
                    await output.WriteAsync(buffer.AsMemory(0, count), cancel);
                    amount -= count;
                }
            }
            if (chunked)
            {
                for (int chunk = 0; chunk < 10000000; chunk++)
                {
                    string line = await LineAsync(cancel);
                    string hex = line.Split(';', 2)[0];
                    if (!long.TryParse(hex, NumberStyles.HexNumber,
                        CultureInfo.InvariantCulture, out long amount) ||
                        amount < 0) throw new IOException("Invalid HTTP chunk length");
                    if (amount == 0)
                    {
                        // Consume any trailer lines.
                        while ((await LineAsync(cancel)).Length > 0) { }
                        return;
                    }
                    await CopyExact(amount);
                    if (await ByteAsync(cancel) != '\r' ||
                        await ByteAsync(cancel) != '\n')
                        throw new IOException("Invalid HTTP chunk separator");
                }
                throw new IOException("Too many HTTP chunks");
            }
            if (length is null)
                throw new IOException("HTTP request body length is missing");
            await CopyExact(length.Value);
        }
    }

    private static string[]? DecodePath(string raw, string nonce)
    {
        string path = raw.Split('?', 2)[0];
        string prefix = "/" + nonce;
        if (path != prefix && !path.StartsWith(prefix + "/", StringComparison.Ordinal))
            return null;
        string rest = path.Length == prefix.Length ? "" : path[(prefix.Length + 1)..];
        if (string.IsNullOrEmpty(rest)) return [];
        var segments = rest.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (segments.Length > 64) return null;
        var output = new List<string>();
        foreach (string part in segments)
        {
            string value = Uri.UnescapeDataString(part);
            if (value.Length is 0 or > 255 || value is "." or ".." ||
                value.IndexOfAny(['/', '\\', '\0', '\r', '\n']) >= 0) return null;
            output.Add(value);
        }
        return output.ToArray();
    }

    private string Href(string[] parts, bool directory)
    {
        string path = string.Join("/", parts.Select(Uri.EscapeDataString));
        return "/" + nonce + "/" + path + (directory && path.Length > 0 ? "/" : "");
    }

    private XElement Response(string[] parts, LegacyEntry entry)
    {
        string modified = entry.Modified.UtcDateTime.ToString("R", CultureInfo.InvariantCulture);
        return new XElement(Dav + "response",
            new XElement(Dav + "href", Href(parts, entry.IsDirectory)),
            new XElement(Dav + "propstat",
                new XElement(Dav + "prop",
                    new XElement(Dav + "displayname", entry.Name),
                    new XElement(Dav + "resourcetype",
                        entry.IsDirectory ? new XElement(Dav + "collection") : null),
                    new XElement(Dav + "getcontentlength", entry.IsDirectory ? 0 : entry.Size),
                    new XElement(Dav + "getlastmodified", modified),
                    new XElement(Dav + "creationdate",
                        entry.Modified.UtcDateTime.ToString("O")),
                    new XElement(Dav + "getetag",
                        "\"" + entry.Size + "-" + entry.Modified.ToUnixTimeSeconds() + "\"")),
                new XElement(Dav + "status", "HTTP/1.1 200 OK")));
    }

    private static async Task ReplyAsync(NetworkStream stream,
        int status, string description, byte[] data, string mime,
        bool head = false, Dictionary<string,string>? extras = null,
        CancellationToken cancel = default)
    {
        var header = new StringBuilder("HTTP/1.1 ").Append(status).Append(' ')
            .Append(description).Append("\r\nConnection: close\r\n")
            .Append("Content-Type: ").Append(mime).Append("\r\n")
            .Append("X-Content-Type-Options: nosniff\r\n")
            .Append("Content-Length: ")
            .Append(extras?.GetValueOrDefault("Content-Length") ??
                    data.Length.ToString(CultureInfo.InvariantCulture))
            .Append("\r\n");
        if (extras is not null)
            foreach (var pair in extras)
                if (pair.Key != "Content-Length")
                    header.Append(pair.Key).Append(": ").Append(pair.Value).Append("\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(header.Append("\r\n").ToString()), cancel);
        if (!head && data.Length > 0) await stream.WriteAsync(data, cancel);
    }

    private async Task ServeAsync(TcpClient socket)
    {
        using (socket)
        using (var stream = socket.GetStream())
        using (var timed = CancellationTokenSource.CreateLinkedTokenSource(stopping.Token))
        {
            timed.CancelAfter(TimeSpan.FromMinutes(30));
            var cancel = timed.Token;
            var reader = new RequestReader(stream);
            string method = "";
            try
            {
                string[] request = (await reader.LineAsync(cancel)).Split(' ', 3);
                if (request.Length < 3) return;
                method = request[0].ToUpperInvariant();
                var headers = new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
                int headersLength = 0;
                while (true)
                {
                    string line = await reader.LineAsync(cancel);
                    headersLength += line.Length;
                    if (headersLength > 32768) return;
                    if (line.Length == 0) break;
                    int colon = line.IndexOf(':');
                    if (colon > 0) headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
                }
                string[]? parts = DecodePath(request[1], nonce);
                if (parts is null)
                {
                    await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                    return;
                }
                if (method == "OPTIONS")
                {
                    await ReplyAsync(stream, 200, "OK", [], "text/plain",
                        extras: new() { ["DAV"] = "1",
                            ["Allow"] = allowWrites ?
                                "OPTIONS, GET, HEAD, PROPFIND, PUT, MKCOL, MOVE, COPY, DELETE" :
                                "OPTIONS, GET, HEAD, PROPFIND",
                            ["MS-Author-Via"] = "DAV" }, cancel: cancel);
                    return;
                }
                if (!allowWrites && method is not ("GET" or "HEAD" or "PROPFIND"))
                {
                    await ReplyAsync(stream, 403, "Forbidden", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                // Accept binary PUT and XML PROPFIND bodies with both fixed
                // lengths and chunked transfer, never decoding bytes as text.
                bool chunked = headers.TryGetValue("Transfer-Encoding", out var transfer) &&
                    transfer.Contains("chunked", StringComparison.OrdinalIgnoreCase);
                long? contentLength = headers.TryGetValue("Content-Length", out var declared) &&
                    long.TryParse(declared, out long length) && length >= 0 ? length : null;
                if (headers.TryGetValue("Expect", out var expect) &&
                    expect.Contains("100-continue", StringComparison.OrdinalIgnoreCase))
                    await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 100 Continue\r\n\r\n"), cancel);
                await storeGate.WaitAsync(cancel);
                try
                {
                    string relative = string.Join("/", parts);
                    var entry = storage.Stat(relative);
                    if (method == "PROPFIND")
                    {
                        if (chunked || contentLength.GetValueOrDefault() > 0)
                            await reader.ReadBodyAsync(Stream.Null, contentLength, chunked, cancel);
                        if (entry is null)
                        {
                            await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                            return;
                        }
                        if (headers.TryGetValue("Depth", out var depth) && depth is not ("0" or "1"))
                        {
                            await ReplyAsync(stream, 403, "Forbidden", [], "text/plain", cancel: cancel);
                            return;
                        }
                        var multistatus = new XElement(Dav + "multistatus", Response(parts, entry));
                        if (entry.IsDirectory && headers.GetValueOrDefault("Depth") != "0")
                            foreach (var child in storage.List(relative))
                                multistatus.Add(Response([..parts, child.Name], child));
                        byte[] xml = Encoding.UTF8.GetBytes(new XDocument(multistatus)
                            .ToString(SaveOptions.DisableFormatting));
                        await ReplyAsync(stream, 207, "Multi-Status", xml,
                            "application/xml; charset=utf-8", cancel: cancel);
                        return;
                    }
                    if (method is "HEAD" or "GET")
                    {
                        if (entry is null || entry.IsDirectory)
                        {
                            await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                            return;
                        }
                        long offset = 0, count = entry.Size;
                        bool ranged = false;
                        if (headers.TryGetValue("Range", out var range))
                        {
                            if (!range.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase))
                                throw new IOException("Unsupported HTTP range");
                            string[] ranges = range[6..].Split('-', 2);
                            if (ranges.Length != 2 ||
                                !long.TryParse(ranges[0], out offset) ||
                                offset >= entry.Size || offset < 0 ||
                                ranges[1].Length > 0 &&
                                (!long.TryParse(ranges[1], out long last) ||
                                 last < offset))
                            {
                                await ReplyAsync(stream, 416, "Range Not Satisfiable", [],
                                    "text/plain", cancel: cancel); return;
                            }
                            count = ranges[1].Length == 0 ? entry.Size - offset :
                                Math.Min(entry.Size - offset, long.Parse(ranges[1]) - offset + 1);
                            ranged = true;
                        }
                        var extra = new Dictionary<string,string> {
                            ["Content-Length"] = count.ToString(CultureInfo.InvariantCulture),
                            ["Accept-Ranges"] = "bytes"
                        };
                        if (ranged) extra["Content-Range"] =
                            $"bytes {offset}-{offset + count - 1}/{entry.Size}";
                        await ReplyAsync(stream, ranged ? 206 : 200,
                            ranged ? "Partial Content" : "OK", [],
                            "application/octet-stream", true, extra, cancel);
                        if (method == "GET")
                        {
                            using var input = storage.OpenRead(relative, offset);
                            var buf = new byte[64 * 1024];
                            while (count > 0)
                            {
                                int n = await input.ReadAsync(buf.AsMemory(0,
                                    (int)Math.Min(count, buf.Length)), cancel);
                                if (n <= 0) throw new EndOfStreamException("Remote file truncated");
                                await stream.WriteAsync(buf.AsMemory(0, n), cancel);
                                count -= n;
                            }
                        }
                        return;
                    }
                    if (method == "PUT")
                    {
                        if (parts.Length == 0) throw new IOException("Root write denied");
                        string recovery = Path.Combine(AppStore.DirectoryPath,
                            "legacy-upload-recovery");
                        Directory.CreateDirectory(recovery);
                        string pending = Path.Combine(recovery,
                            Guid.NewGuid().ToString("N") + ".pending");
                        bool uploaded = false;
                        try
                        {
                            await using (var target = new FileStream(pending, FileMode.CreateNew,
                                FileAccess.Write, FileShare.None, 1024 * 128, true))
                            {
                                await reader.ReadBodyAsync(target, contentLength, chunked, cancel);
                                await target.FlushAsync(cancel);
                                target.Flush(flushToDisk: true);
                            }
                            CommitUpload(relative, pending);
                            uploaded = true;
                            await ReplyAsync(stream, entry is null ? 201 : 204,
                                entry is null ? "Created" : "No Content", [],
                                "text/plain", cancel: cancel);
                        }
                        finally
                        {
                            if (uploaded) File.Delete(pending);
                            else AppStore.Log("LEGACY_CHARSET_PENDING_UPLOAD path=" + pending +
                                " (retained_for_recovery)");
                        }
                        return;
                    }
                    if (method == "MKCOL")
                    {
                        if (parts.Length == 0 || entry is not null)
                        {
                            await ReplyAsync(stream, 405, "Method Not Allowed", [], "text/plain",
                                cancel: cancel); return;
                        }
                        storage.CreateDirectory(relative);
                        await ReplyAsync(stream, 201, "Created", [], "text/plain", cancel: cancel);
                        return;
                    }
                    if (method == "DELETE")
                    {
                        if (parts.Length == 0 || entry is null)
                        {
                            await ReplyAsync(stream, 404, "Not Found", [], "text/plain",
                                cancel: cancel); return;
                        }
                        storage.Delete(relative, entry.IsDirectory);
                        await ReplyAsync(stream, 204, "No Content", [], "text/plain", cancel: cancel);
                        return;
                    }
                    if (method is "MOVE" or "COPY")
                    {
                        if (parts.Length == 0 || entry is null ||
                            !headers.TryGetValue("Destination", out var dest) ||
                            !Uri.TryCreate(dest, UriKind.Absolute, out var uri) ||
                            !Uri.TryCreate(Url, UriKind.Absolute, out var own) ||
                            uri.Host != own.Host || uri.Port != own.Port ||
                            DecodePath(uri.AbsolutePath, nonce) is not { Length: > 0 } target)
                        {
                            await ReplyAsync(stream, 403, "Forbidden", [], "text/plain",
                                cancel: cancel); return;
                        }
                        string newPath = string.Join("/", target);
                        if (relative == newPath)
                        {
                            await ReplyAsync(stream, 204, "No Content", [], "text/plain", cancel: cancel);
                            return;
                        }
                        if (storage.Stat(newPath) is not null)
                        {
                            // Never silently destroy an existing destination.
                            await ReplyAsync(stream, 412, "Precondition Failed", [],
                                "text/plain", cancel: cancel);
                            return;
                        }
                        if (method == "MOVE") storage.Rename(relative, newPath);
                        else if (entry.IsDirectory)
                        {
                            await ReplyAsync(stream, 405, "Method Not Allowed", [],
                                "text/plain", cancel: cancel); return;
                        }
                        else
                        {
                            string pending = Path.Combine(AppStore.DirectoryPath,
                                "legacy-upload-recovery", Guid.NewGuid().ToString("N") + ".pending");
                            Directory.CreateDirectory(Path.GetDirectoryName(pending)!);
                            bool committed = false;
                            try
                            {
                                using (var input = storage.OpenRead(relative, 0))
                                using (var file = File.Create(pending))
                                    input.CopyTo(file);
                                CommitUpload(newPath, pending);
                                committed = true;
                            }
                            finally
                            {
                                if (committed && File.Exists(pending)) File.Delete(pending);
                                else if (!committed)
                                    AppStore.Log("LEGACY_CHARSET_PENDING_COPY path=" +
                                        pending + " (retained_for_recovery)");
                            }
                        }
                        await ReplyAsync(stream, 201, "Created", [], "text/plain", cancel: cancel);
                        return;
                    }
                    await ReplyAsync(stream, 405, "Method Not Allowed", [],
                        "text/plain", cancel: cancel);
                }
                finally { storeGate.Release(); }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                // Never log passwords, full remote names or HTTP request bytes.
                AppStore.Log("LEGACY_CHARSET_GATEWAY_ERROR method=" + method +
                    " type=" + ex.GetType().Name + " details_redacted");
                try { await ReplyAsync(stream, 502, "Bad Gateway", [],
                    "text/plain", cancel: cancel); } catch { }
            }
        }
    }

    private void CommitUpload(string final, string pending)
    {
        string id = Guid.NewGuid().ToString("N");
        string directory = final.Contains('/') ? final[..final.LastIndexOf('/')] + "/" : "";
        string staging = directory + ".nasmount-upload-" + id + ".tmp";
        long count = new FileInfo(pending).Length;
        storage.Upload(staging, pending);
        if (storage.Stat(staging) is not { IsDirectory: false } uploaded ||
            uploaded.Size != count)
            throw new IOException("원격 임시 업로드 크기 확인에 실패했습니다.");
        string? backup = null;
        if (storage.Stat(final) is not null)
        {
            backup = directory + ".nasmount-backup-" + id;
            storage.Rename(final, backup);
        }
        try
        {
            storage.Rename(staging, final);
            if (storage.Stat(final)?.Size != count)
                throw new IOException("원격 업로드 완료 확인에 실패했습니다.");
            if (backup is not null)
                storage.Delete(backup, directory: false);
        }
        catch
        {
            try
            {
                if (backup is not null && storage.Stat(final) is null)
                    storage.Rename(backup, final);
            }
            catch { /* Retain remote backup; never discard the only copy. */ }
            throw;
        }
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        stopping.Cancel();
        listener.Stop();
        // Pending streams are cancelled only after the owner has verified
        // rclone upload completion and the mount has stopped.
        storage.Dispose();
    }
}
