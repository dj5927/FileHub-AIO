using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Carefully scoped WebDAV authoring adapter for MYBOX's official REST API.
/// Does not claim transactional Office or cross-machine file locking.
/// Failed PUT bodies are retained in the app's recovery directory.
/// </summary>
internal sealed partial class MyBoxBetaGateway
{
    private readonly SemaphoreSlim writeGate = new(1, 1);
    internal static MyBoxBetaGateway CreateLocalWriteLab(Api client)
    {
        var gateway = new MyBoxBetaGateway(client, writable: true);
        gateway.acceptLoop = Task.Run(gateway.AcceptAsync);
        return gateway;
    }

    private static async Task<string?> ReadHttpLineAsync(
        Stream source, CancellationToken cancel)
    {
        using var result = new MemoryStream();
        var single = new byte[1];
        while (result.Length < 16 * 1024)
        {
            if (await source.ReadAsync(single, cancel) == 0)
                return result.Length == 0 ? null : throw new IOException("Incomplete HTTP line.");
            if (single[0] == 10)
            {
                var chars = result.ToArray();
                int size = chars.Length > 0 && chars[^1] == 13 ?
                    chars.Length - 1 : chars.Length;
                return Encoding.ASCII.GetString(chars, 0, size);
            }
            result.WriteByte(single[0]);
        }
        throw new IOException("Oversized HTTP header.");
    }

    private static async Task CopyExactAsync(Stream from, Stream to,
        long length, CancellationToken cancel)
    {
        var buffer = new byte[128 * 1024];
        while (length > 0)
        {
            int n = await from.ReadAsync(buffer.AsMemory(
                0, (int)Math.Min(buffer.Length, length)), cancel);
            if (n == 0) throw new IOException("Upload body ended prematurely.");
            await to.WriteAsync(buffer.AsMemory(0, n), cancel);
            length -= n;
        }
    }

    private static async Task DrainRequestBodyAsync(Stream source, long? length,
        bool chunked, CancellationToken cancel)
    {
        if (chunked)
        {
            while (true)
            {
                string line = await ReadHttpLineAsync(source, cancel) ??
                    throw new IOException("Malformed chunked request body");
                if (!long.TryParse(line.Split(';', 2)[0], NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture, out long size) || size < 0 ||
                    size > 1024 * 1024)
                    throw new IOException("Excessive WebDAV metadata body");
                if (size == 0)
                {
                    for (int i = 0; i < 16; i++)
                        if ((await ReadHttpLineAsync(source, cancel)) is "" or null) break;
                    break;
                }
                await CopyExactAsync(source, Stream.Null, size, cancel);
                if (await ReadHttpLineAsync(source, cancel) != "")
                    throw new IOException("Invalid WebDAV metadata chunk");
            }
        }
        else if (length is > 0 and <= 1024 * 1024)
            await CopyExactAsync(source, Stream.Null, length.Value, cancel);
        else if (length > 1024 * 1024)
            throw new IOException("Oversized WebDAV metadata request body");
    }

    private static async Task<long> BufferPutAsync(Stream body,
        string target, long? length, bool chunked, CancellationToken cancel)
    {
        const long maximum = 10L * 1024 * 1024 * 1024; // local staging ceiling
        if (length > maximum) throw new IOException("MYBOX upload exceeds staging limit.");
        await using var output = new FileStream(target, FileMode.CreateNew,
            FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous);
        if (chunked)
        {
            long copied = 0;
            while (true)
            {
                string line = await ReadHttpLineAsync(body, cancel) ??
                    throw new IOException("Missing chunk size");
                string hex = line.Split(';', 2)[0].Trim();
                if (!long.TryParse(hex, NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture, out long size) || size < 0 ||
                    size > maximum - copied)
                    throw new IOException("Invalid chunked upload size");
                if (size == 0)
                {
                    // Consume chunk trailer; never use trailer content as commands.
                    for (int i = 0; i < 32; i++)
                        if ((await ReadHttpLineAsync(body, cancel)) is "" or null) break;
                    break;
                }
                await CopyExactAsync(body, output, size, cancel);
                copied += size;
                if (await ReadHttpLineAsync(body, cancel) != "")
                    throw new IOException("Invalid chunk framing");
            }
            await output.FlushAsync(cancel);
            return copied;
        }
        if (length is null)
            throw new IOException("PUT requires Content-Length or chunked encoding.");
        await CopyExactAsync(body, output, length.Value, cancel);
        await output.FlushAsync(cancel);
        return length.Value;
    }

    private string[]? ParseDestination(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return null;
        string raw = header;
        if (Uri.TryCreate(header, UriKind.Absolute, out var uri))
        {
            if (uri.Host != "127.0.0.1" ||
                !Uri.TryCreate(Url, UriKind.Absolute, out var root) ||
                uri.Port != root.Port || uri.Scheme != Uri.UriSchemeHttp)
                return null;
            raw = uri.AbsolutePath;
        }
        return SplitPath(raw, nonce);
    }

    private async Task<(Entry? Item, string? ParentId)> ParentForAsync(
        string[] pieces, CancellationToken cancel)
    {
        if (pieces.Length == 0) throw new IOException("Root is not writable");
        if (pieces.Length == 1) return (null, null);
        var parent = await api.ResolveAsync(pieces[..^1], cancel);
        if (parent is null || !parent.IsFolder)
            throw new DirectoryNotFoundException("MYBOX parent folder not found");
        return (parent, parent.Id);
    }

    private async Task HandleAuthoringAsync(NetworkStream stream, string method,
        string[] pieces, string? destination, string? overwrite,
        long? bodyLength, bool chunked, CancellationToken cancel)
    {
        await writeGate.WaitAsync(cancel);
        try
        {
            // ReadOnly -> writable is explicit per-profile. Malformed paths and
            // unauthorised destination origins must fail closed.
            if (pieces.Length == 0)
            {
                await ReplyAsync(stream, 403, "Forbidden", [], "text/plain", cancel: cancel);
                return;
            }
            var existing = await api.ResolveAsync(pieces, cancel);
            var (_, parentId) = await ParentForAsync(pieces, cancel);
            if (method == "PUT")
            {
                if (existing?.IsFolder == true ||
                    existing is not null && overwrite == "F")
                {
                    await ReplyAsync(stream, 412, "Precondition Failed", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                // Reversible local staging, never upload an incomplete stream.
                string folder = Path.Combine(AppStore.DirectoryPath, "mybox-upload-recovery");
                Directory.CreateDirectory(folder);
                string staged = Path.Combine(folder,
                    DateTime.UtcNow.ToString("yyyyMMddTHHmmss", CultureInfo.InvariantCulture) +
                    "_" + Convert.ToHexString(RandomNumberGenerator.GetBytes(12)) + ".pending");
                bool complete = false;
                try
                {
                    long size = await BufferPutAsync(stream, staged,
                        bodyLength, chunked, cancel);
                    await api.UploadFileAsync(staged, pieces[^1], size,
                        parentId, existing is not null, cancel);
                    await api.InvalidateCacheAsync(cancel);
                    // MYBOX metadata may take a short time to reflect a write.
                    for (int attempt = 0; attempt < 3; attempt++)
                    {
                        var verified = await api.ResolveAsync(pieces, cancel);
                        if (verified is not null && !verified.IsFolder &&
                            verified.Size == size)
                        {
                            complete = true;
                            break;
                        }
                        await Task.Delay(300, cancel);
                        await api.InvalidateCacheAsync(cancel);
                    }
                    if (!complete)
                        throw new IOException("MYBOX upload not confirmed by file metadata.");
                    File.Delete(staged);
                    await ReplyAsync(stream, existing is null ? 201 : 204,
                        existing is null ? "Created" : "No Content", [], "text/plain",
                        cancel: cancel);
                }
                catch
                {
                    // Keep exact uploaded bytes for manual recovery and log
                    // the local file, never delete the remote old version.
                    AppStore.Log("MYBOX_WRITE_RECOVERY_FILE " + staged);
                    throw;
                }
                return;
            }
            if (method == "MKCOL")
            {
                if (existing is not null)
                {
                    await ReplyAsync(stream, 405, "Already Exists", [], "text/plain", cancel: cancel);
                    return;
                }
                await api.CreateFolderAsync(pieces[^1], parentId, cancel);
                await api.InvalidateCacheAsync(cancel);
                await ReplyAsync(stream, 201, "Created", [], "text/plain", cancel: cancel);
                return;
            }
            if (existing is null)
            {
                await ReplyAsync(stream, 404, "Not Found", [], "text/plain", cancel: cancel);
                return;
            }
            if (method == "DELETE")
            {
                // Official DELETE moves to MYBOX trash, not permanent deletion.
                await api.TrashAsync(existing.Id, cancel);
                await api.InvalidateCacheAsync(cancel);
                await ReplyAsync(stream, 204, "No Content", [], "text/plain", cancel: cancel);
                return;
            }
            if (method is "MOVE" or "COPY")
            {
                var to = ParseDestination(destination);
                if (to is null || to.Length == 0)
                {
                    await ReplyAsync(stream, 400, "Invalid Destination", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                var (_, toParentId) = await ParentForAsync(to, cancel);
                bool sameParent = string.Equals(parentId, toParentId, StringComparison.Ordinal);
                bool sameName = pieces[^1].Equals(to[^1], StringComparison.Ordinal);
                var conflict = await api.ResolveAsync(to, cancel);
                if (conflict is not null && overwrite == "F")
                {
                    await ReplyAsync(stream, 412, "Precondition Failed", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                if (method == "COPY")
                    await api.CopyAsync(existing.Id, toParentId, to[^1],
                        conflict is not null, cancel);
                else if (sameParent && !sameName)
                {
                    // MYBOX rename endpoint has no overwrite flag. Avoid
                    // deleting a conflicting destination to emulate WebDAV.
                    if (conflict is not null)
                    {
                        await ReplyAsync(stream, 412, "Rename Conflict", [],
                            "text/plain", cancel: cancel);
                        return;
                    }
                    await api.RenameAsync(existing.Id, to[^1], cancel);
                }
                else if (!sameParent && sameName)
                    await api.MoveAsync(existing.Id, toParentId, conflict is not null, cancel);
                else if (!sameParent)
                {
                    // Rename + move is not atomic in the official API.
                    await ReplyAsync(stream, 409, "Combined Rename And Move Unsupported", [],
                        "text/plain", cancel: cancel);
                    return;
                }
                await api.InvalidateCacheAsync(cancel);
                await ReplyAsync(stream, conflict is null ? 201 : 204,
                    conflict is null ? "Created" : "No Content", [], "text/plain", cancel: cancel);
                return;
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex)
        {
            AppStore.Log("MYBOX_WRITE_FAILED " + ex.GetType().Name +
                " " + ex.Message.Replace("\r", "").Replace("\n", " "));
            try { await ReplyAsync(stream, 502, "Remote Write Failed", [],
                "text/plain", cancel: cancel); } catch { }
        }
        finally { writeGate.Release(); }
    }

    internal sealed partial class Api
    {
        private readonly HttpClient uploadClient;

        internal async Task InvalidateCacheAsync(CancellationToken cancel)
        {
            await gate.WaitAsync(cancel);
            try { cache.Clear(); }
            finally { gate.Release(); }
        }
        private async Task CallAsync(HttpMethod method, string endpoint,
            object? payload, CancellationToken cancel, HttpStatusCode expected)
        {
            using var request = new HttpRequestMessage(method, endpoint);
            if (payload is not null)
                request.Content = new StringContent(JsonSerializer.Serialize(payload),
                    Encoding.UTF8, "application/json");
            using var response = await http.SendAsync(request, cancel);
            if (response.StatusCode != expected)
                throw new IOException("MYBOX API write failed (HTTP " + (int)response.StatusCode + ")");
        }
        internal async Task CreateFolderAsync(string name, string? parentId,
            CancellationToken cancel)
        {
            var body = parentId is null ? new { folderName = name } :
                (object)new { folderName = name, parentId };
            await CallAsync(HttpMethod.Post, "drive/folders", body,
                cancel, HttpStatusCode.Created);
        }
        internal async Task RenameAsync(string id, string name, CancellationToken cancel) =>
            await CallAsync(HttpMethod.Post, "drive/resources/" + Uri.EscapeDataString(id) +
                "/rename", new { name }, cancel, HttpStatusCode.OK);
        internal async Task MoveAsync(string id, string? parentId,
            bool overwrite, CancellationToken cancel)
        {
            if (parentId is null)
                throw new IOException("MYBOX API cannot move a file to root with an absent parentId.");
            await CallAsync(HttpMethod.Post, "drive/resources/" +
                Uri.EscapeDataString(id) + "/move",
                new { parentId, isOverwrite = overwrite }, cancel, HttpStatusCode.OK);
        }
        internal async Task CopyAsync(string id, string? parentId,
            string name, bool overwrite, CancellationToken cancel)
        {
            object body = parentId is null ?
                new { name, isOverwrite = overwrite } :
                new { name, parentId, isOverwrite = overwrite };
            await CallAsync(HttpMethod.Post, "drive/resources/" +
                Uri.EscapeDataString(id) + "/copy", body, cancel, HttpStatusCode.Created);
        }
        internal async Task TrashAsync(string id, CancellationToken cancel) =>
            await CallAsync(HttpMethod.Delete, "drive/resources/" +
                Uri.EscapeDataString(id), null, cancel, HttpStatusCode.NoContent);
        internal async Task UploadFileAsync(string staged, string name, long size,
            string? parentId, bool overwrite, CancellationToken cancel)
        {
            object payload = parentId is null ?
                new { fileName = name, fileSize = size, isOverwrite = overwrite } :
                new { fileName = name, fileSize = size, isOverwrite = overwrite, parentId };
            using var request = new HttpRequestMessage(HttpMethod.Post, "drive/files") {
                Content = new StringContent(JsonSerializer.Serialize(payload),
                    Encoding.UTF8, "application/json")
            };
            using var response = await http.SendAsync(request, cancel);
            if (response.StatusCode != HttpStatusCode.Created)
                throw new IOException("MYBOX upload URL request failed (HTTP " +
                    (int)response.StatusCode + ")");
            using var json = JsonDocument.Parse(
                await response.Content.ReadAsStringAsync(cancel));
            string? raw = json.RootElement.GetProperty("uploadUrl").GetString();
            if (!Uri.TryCreate(raw, UriKind.Absolute, out var url) ||
                !AllowedDownloadUrl(url))
                throw new IOException("Unsafe MYBOX upload endpoint");
            await using var file = new FileStream(staged, FileMode.Open,
                FileAccess.Read, FileShare.Read, 128 * 1024, FileOptions.Asynchronous);
            using var form = new MultipartFormDataContent();
            using var content = new StreamContent(file);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            form.Add(content, "Filedata", name);
            using var uploaded = await uploadClient.PostAsync(url, form, cancel);
            if (!uploaded.IsSuccessStatusCode)
                throw new IOException("MYBOX binary upload failed (HTTP " +
                    (int)uploaded.StatusCode + ")");
            using var data = JsonDocument.Parse(
                await uploaded.Content.ReadAsStringAsync(cancel));
            if (data.RootElement.TryGetProperty("fileSize", out var reported) &&
                reported.TryGetInt64(out long actual) && actual != size)
                throw new IOException("MYBOX upload length mismatch");
        }
    }
}
