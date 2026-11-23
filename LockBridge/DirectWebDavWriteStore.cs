using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount.LockBridge;

/// <summary>
/// Durable, conditional WebDAV write sessions for the one-WinFsp provider.
/// Never silently overwrite a changed NAS document. Failed uploads remain
/// on local disk with a recovery manifest; only verified commits are retired.
/// This isolated implementation is not connected to real user credentials.
/// </summary>
internal sealed class DirectWebDavWriteStore : IDisposable
{
    private sealed class NonClosingStream(Stream source) : Stream
    {
        public override bool CanRead => source.CanRead;
        public override bool CanSeek => source.CanSeek;
        public override bool CanWrite => source.CanWrite;
        public override long Length => source.Length;
        public override long Position { get => source.Position; set => source.Position = value; }
        public override void Flush() => source.Flush();
        public override int Read(byte[] b, int o, int n) => source.Read(b, o, n);
        public override long Seek(long offset, SeekOrigin origin) => source.Seek(offset, origin);
        public override void SetLength(long value) => source.SetLength(value);
        public override void Write(byte[] b, int o, int n) => source.Write(b, o, n);
        protected override void Dispose(bool disposing) { /* the session owns source */ }
    }
    internal sealed record Journal(string Id, string Path, string? ExpectedEtag,
        bool Created, DateTime CreatedUtc, long Size, string Status,
        long WrittenBytes = -1, bool SequentialWrites = false);

    internal sealed class Handle : IDisposable
    {
        internal readonly DirectWebDavWriteStore Owner;
        internal readonly string Id;
        internal readonly string LocalPath;
        internal string RemotePath;
        internal string? ExpectedEtag;
        internal bool Created;
        internal bool Dirty;
        // A previous PUT may already have reached the NAS even when the
        // follow-up verification failed. Do not retry with a stale ETag.
        internal bool UploadMayHaveCommitted;
        internal bool Closed;
        internal long ContiguousWritten;
        internal bool SequentialWrites = true;
        internal long PlannedCopySize;
        internal DirectWebDavProgressiveUpload? Progressive;
        internal bool ProgressiveUnavailable;
        internal readonly List<string> AbandonedStages = [];
        internal readonly FileStream Local;
        internal readonly object Gate = new();
        internal Handle(DirectWebDavWriteStore owner, string id, string path,
            string remote, string? etag, bool created, FileStream stream)
        {
            Owner = owner; Id = id; LocalPath = path; RemotePath = remote;
            ExpectedEtag = etag; Created = created; Dirty = created;
            Local = stream;
        }
        public void Dispose()
        {
            if (Closed) return;
            Closed = true;
            Local.Dispose();
        }
    }

    private readonly Uri _root;
    private readonly HttpClient _http;
    private readonly string _journalRoot;
    internal string JournalDirectory => _journalRoot;
    private readonly DirectWebDavReadOnlyBackend _read;
    private readonly ConcurrentDictionary<string, object> _locks =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly object _capabilityGate = new();
    private bool _conditionalWritesVerified;
    private bool _exclusiveMkcolVerified;
    private readonly HashSet<string> _safeStageMoveDirectories =
        new(StringComparer.OrdinalIgnoreCase);

    // MOVE Overwrite:F cannot be assumed to work uniformly on all WebDAV
    // servers. Probe ONLY on private, unique objects in the target directory
    // before allowing a progressive stage to be published by MOVE.
    private void RequireVerifiedStageMove(string destination)
    {
        string parent = destination[
            ..(destination.LastIndexOf('/') + 1)];
        lock (_capabilityGate)
        {
            if (_safeStageMoveDirectories.Contains(parent)) return;
            string nonce = Guid.NewGuid().ToString("N");
            string source = parent + ".nasmount-stage-move-probe-" + nonce;
            string occupied = parent + ".nasmount-stage-occupied-" + nonce;
            string fresh = parent + ".nasmount-stage-fresh-" + nonce;
            byte[] sourceData = Encoding.ASCII.GetBytes("filehub-stage-source");
            byte[] targetData = Encoding.ASCII.GetBytes("filehub-target-keep");
            void PutOwned(string path, byte[] data)
            {
                using var put = new HttpRequestMessage(HttpMethod.Put, Remote(path));
                put.Headers.TryAddWithoutValidation("If-None-Match", "*");
                put.Content = new ByteArrayContent(data);
                using var reply = _http.Send(put);
                if (!reply.IsSuccessStatusCode)
                    throw new IOException("Progressive MOVE capability create HTTP " +
                        (int)reply.StatusCode);
            }
            HttpStatusCode MoveOwned(string from, string to)
            {
                using var move = new HttpRequestMessage(new HttpMethod("MOVE"),
                    Remote(from));
                move.Headers.TryAddWithoutValidation("Destination", Remote(to).AbsoluteUri);
                move.Headers.TryAddWithoutValidation("Overwrite", "F");
                using var reply = _http.Send(move);
                return reply.StatusCode;
            }
            try
            {
                PutOwned(source, sourceData);
                PutOwned(occupied, targetData);
                var rejected = MoveOwned(source, occupied);
                if (rejected is not (HttpStatusCode.PreconditionFailed or
                    HttpStatusCode.Conflict or HttpStatusCode.MethodNotAllowed))
                    throw new IOException("Server did not reject an occupied MOVE target");
                if (!_http.GetByteArrayAsync(Remote(occupied)).GetAwaiter()
                    .GetResult().SequenceEqual(targetData))
                    throw new IOException("Server overwrote occupied MOVE target");
                var moved = MoveOwned(source, fresh);
                if (moved is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
                    throw new IOException("Server does not support guarded MOVE");
                if (!_http.GetByteArrayAsync(Remote(fresh)).GetAwaiter()
                    .GetResult().SequenceEqual(sourceData))
                    throw new IOException("MOVE destination bytes incorrect");
                _safeStageMoveDirectories.Add(parent);
                Console.WriteLine("DIRECT_PROGRESSIVE_GUARDED_MOVE_PROBE_PASS");
            }
            finally
            {
                foreach (string item in new[] { source, occupied, fresh })
                {
                    try
                    {
                        using var reply = _http.DeleteAsync(Remote(item))
                            .GetAwaiter().GetResult();
                    }
                    catch (HttpRequestException) { }
                }
            }
        }
    }

    internal DirectWebDavWriteStore(Uri root, DirectWebDavReadOnlyBackend read,
        string journalRoot, HttpClient? authenticated = null)
        : this(root, read, journalRoot, authenticated, requireLoopback: true)
    {
    }

    internal DirectWebDavWriteStore(NasProfile profile, string password,
        DirectWebDavReadOnlyBackend read, string journalRoot)
        : this(ExcelAtomicBridge.ProfileRoot(profile), read, journalRoot,
            CreateAuthenticatedClient(profile, password), requireLoopback: false)
    {
    }

    private static HttpClient CreateAuthenticatedClient(NasProfile profile,
        string password)
    {
        if (profile.Protocol != "WebDAV" ||
            (!profile.IsAnonymous && string.IsNullOrEmpty(password)))
            throw new ArgumentException("Direct WebDAV requires a valid profile and credentials");
        var client = WebDavTlsPolicy.CreateClient(profile, TimeSpan.FromSeconds(120),
            allowRedirect: false);
        if (!profile.IsAnonymous)
            client.DefaultRequestHeaders.Authorization =
                new AuthenticationHeaderValue("Basic", Convert.ToBase64String(
                    Encoding.UTF8.GetBytes(profile.Username + ":" + password)));
        return client;
    }

    private DirectWebDavWriteStore(Uri root, DirectWebDavReadOnlyBackend read,
        string journalRoot, HttpClient? authenticated, bool requireLoopback)
    {
        if ((requireLoopback && (!root.IsLoopback || root.Scheme != Uri.UriSchemeHttp)) ||
            root.UserInfo.Length != 0 || root.Query.Length != 0 ||
            root.Fragment.Length != 0)
            throw new ArgumentException("Write laboratory only accepts loopback WebDAV");
        _root = new Uri(root.AbsoluteUri.TrimEnd('/') + "/");
        _read = read;
        _journalRoot = Path.GetFullPath(journalRoot);
        Directory.CreateDirectory(_journalRoot);
        _http = authenticated ?? new HttpClient(new HttpClientHandler {
            AllowAutoRedirect = false, UseCookies = false
        }) { Timeout = TimeSpan.FromSeconds(120) };
    }

    internal Uri Remote(string path)
    {
        string relative = DirectWebDavReadOnlyBackend.Normalize(path);
        string escaped = string.Join('/', relative.Split('/',
            StringSplitOptions.RemoveEmptyEntries).Select(Uri.EscapeDataString));
        return new Uri(_root, escaped);
    }

    private string DataPath(string id) => Path.Combine(_journalRoot, id + ".data");
    private string ManifestPath(string id) => Path.Combine(_journalRoot, id + ".json");

    // Some WebDAV servers silently IGNORE If-Match and If-None-Match, even
    // when the client sets both correctly. We reproduced this with the
    // installed rclone serve webdav: a deliberately incorrect If-Match PUT
    // returned HTTP 201 and overwrote the file! Therefore optimistic headers
    // are insufficient. Probe EXCLUSIVELY on a unique application-owned file
    // before allowing any user-document update. Deny all writes on failure.
    internal void RequireVerifiedConditionalWrites(string probeDirectory = "/")
    {
        lock (_capabilityGate)
        {
            if (_conditionalWritesVerified) return;
            // The root of a Synology WebDAV mount is usually only a list of
            // shared folders. PUT to / is forbidden even when PUT inside an
            // individual share works perfectly. Probe in an accessible
            // subfolder instead of confusing root ACLs with HTTP capability.
            string directory = DirectWebDavReadOnlyBackend.Normalize(probeDirectory);
            string name = (directory == "/" ? "" : directory) +
                "/.filehub-conditional-probe-" + Guid.NewGuid().ToString("N");
            Uri url = Remote(name);
            byte[] original = Encoding.UTF8.GetBytes("filehub-probe-original:" + name);
            byte[] forbidden = Encoding.UTF8.GetBytes("filehub-should-never-overwrite:" + name);
            try
            {
                using (var create = new HttpRequestMessage(HttpMethod.Put, url))
                {
                    create.Headers.TryAddWithoutValidation("If-None-Match", "*");
                    create.Content = new ByteArrayContent(original);
                    using var result = _http.Send(create);
                    if (!result.IsSuccessStatusCode)
                        throw new IOException("Conditional probe create HTTP " +
                            (int)result.StatusCode);
                }
                // A second creation attempt MUST be rejected. This proves
                // new-file creation cannot overwrite a concurrent creator.
                using (var duplicate = new HttpRequestMessage(HttpMethod.Put, url))
                {
                    duplicate.Headers.TryAddWithoutValidation("If-None-Match", "*");
                    duplicate.Content = new ByteArrayContent(forbidden);
                    using var result = _http.Send(duplicate);
                    if (result.StatusCode != HttpStatusCode.PreconditionFailed)
                        throw new IOException("Server ignores If-None-Match; unsafe for new files");
                }
                using (var stale = new HttpRequestMessage(HttpMethod.Put, url))
                {
                    stale.Headers.TryAddWithoutValidation("If-Match", "\"filehub-impossible-etag\"");
                    stale.Content = new ByteArrayContent(forbidden);
                    using var result = _http.Send(stale);
                    if (result.StatusCode != HttpStatusCode.PreconditionFailed)
                        throw new IOException("Server ignores If-Match; unsafe for concurrent edits");
                }
                byte[] actual = _http.GetByteArrayAsync(url).GetAwaiter().GetResult();
                if (!actual.SequenceEqual(original))
                    throw new IOException("Condition probe content changed despite rejected overwrite");
                _conditionalWritesVerified = true;
                Console.WriteLine("DIRECT_WEBDAV_CONDITIONAL_PUT_SERVER_ENFORCEMENT_PASS");
            }
            finally
            {
                // We only ever remove the exact private probe name, never
                // user files. Failure to clean the probe does not weaken a
                // rejection decision for user documents.
                try { using var result = _http.DeleteAsync(url).GetAwaiter().GetResult(); }
                catch (HttpRequestException) { }
            }
        }
    }

    internal void RequireExclusiveLockDirectory(string probeDirectory = "/")
    {
        lock (_capabilityGate)
        {
            if (_exclusiveMkcolVerified) return;
            string directory = DirectWebDavReadOnlyBackend.Normalize(probeDirectory);
            string name = (directory == "/" ? "" : directory) +
                "/.filehub-mkcol-probe-" + Guid.NewGuid().ToString("N");
            Uri url = Remote(name);
            try
            {
                using (var first = new HttpRequestMessage(new HttpMethod("MKCOL"), url))
                using (var response = _http.Send(first))
                    if (response.StatusCode != HttpStatusCode.Created)
                        throw new IOException("Exclusive lock probe MKCOL HTTP " +
                            (int)response.StatusCode);
                using (var second = new HttpRequestMessage(new HttpMethod("MKCOL"), url))
                using (var response = _http.Send(second))
                    if (response.StatusCode is not (
                            HttpStatusCode.MethodNotAllowed or HttpStatusCode.Conflict or
                            HttpStatusCode.Locked))
                        throw new IOException("Server did not reliably reject duplicate MKCOL (HTTP " +
                            (int)response.StatusCode + "); unsafe cross-PC locks");
                _exclusiveMkcolVerified = true;
                Console.WriteLine("DIRECT_WEBDAV_EXCLUSIVE_MKCOL_SERVER_ENFORCEMENT_PASS");
            }
            finally
            {
                try { using var response = _http.DeleteAsync(url).GetAwaiter().GetResult(); }
                catch (HttpRequestException) { }
            }
        }
    }

    private void SaveJournal(Handle h, string status)
    {
        h.Local.Flush(true);
        var journal = new Journal(h.Id, h.RemotePath, h.ExpectedEtag,
            h.Created, DateTime.UtcNow, h.Local.Length, status,
            h.ContiguousWritten, h.SequentialWrites);
        string final = ManifestPath(h.Id);
        string temporary = final + ".pending";
        using (var f = new FileStream(temporary, FileMode.Create,
            FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
        {
            JsonSerializer.Serialize(f, journal);
            f.Flush(true);
        }
        File.Move(temporary, final, true);
    }

    internal Handle Begin(string path, bool create)
    {
        RequireVerifiedConditionalWrites();
        string name = DirectWebDavReadOnlyBackend.Normalize(path);
        var current = _read.Stat(name);
        if (create && current is not null)
            throw new IOException("File already exists: " + name);
        if (!create && current is null)
            throw new FileNotFoundException("Existing WebDAV file missing", name);
        if (current?.IsDirectory == true)
            throw new IOException("Cannot edit directory as file");
        if (!create && !StrongEtag(current!.ETag))
            throw new IOException("Cannot safely edit without strong server ETag");

        string id = Guid.NewGuid().ToString("N");
        string local = DataPath(id);
        var stream = new FileStream(local, FileMode.CreateNew,
            FileAccess.ReadWrite, FileShare.Read,
            65536, FileOptions.RandomAccess | FileOptions.WriteThrough);
        try
        {
            if (!create)
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, Remote(name));
                request.Headers.TryAddWithoutValidation("If-Match", current!.ETag);
                using var response = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                if (!response.IsSuccessStatusCode)
                    throw new IOException("WebDAV initial download HTTP " +
                        (int)response.StatusCode);
                using var body = response.Content.ReadAsStream();
                body.CopyTo(stream);
                if (stream.Length != current!.Size)
                    throw new IOException("Remote changed during initial edit download");
                stream.Seek(0, SeekOrigin.Begin);
            }
            var handle = new Handle(this, id, local, name,
                current?.ETag, create, stream);
            SaveJournal(handle, create ? "new-uncommitted" : "opened");
            return handle;
        }
        catch
        {
            stream.Dispose();
            try { File.Delete(local); } catch (IOException) { }
            throw;
        }
    }

    internal static bool StrongEtag(string? etag) =>
        etag is { Length: > 1 } && etag.StartsWith('"') &&
        etag.EndsWith('"');

    internal void Write(Handle handle, ulong offset, ReadOnlySpan<byte> data,
        bool append = false)
    {
        DirectWebDavProgressiveUpload? upload = null;
        long waitFor = 0;
        lock (handle.Gate)
        {
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            if (offset > long.MaxValue) throw new IOException("File offset overflow");
            long position = append ? handle.Local.Length : (long)offset;
            handle.Local.Seek(append ? 0 : (long)offset,
                append ? SeekOrigin.End : SeekOrigin.Begin);
            handle.Local.Write(data);
            handle.Dirty = true;
            if (handle.Created)
            {
                if (handle.SequentialWrites && position == handle.ContiguousWritten)
                    handle.ContiguousWritten += data.Length;
                else if (data.Length != 0)
                    handle.SequentialWrites = false;
            }
            SaveJournal(handle, "dirty");
            // A known allocation alone is not enough to enable streaming.
            // Only a new, sequential, fixed-size non-Office file qualifies.
            if (handle.Progressive is null &&
                !handle.ProgressiveUnavailable && handle.Created &&
                handle.PlannedCopySize >= 2 * 1024 * 1024 &&
                position == 0 && !append && data.Length > 0 &&
                !WriteIntent.IsDocument(handle.RemotePath))
            {
                try
                {
                    RequireVerifiedStageMove(handle.RemotePath);
                    string parent = handle.RemotePath[
                        ..(handle.RemotePath.LastIndexOf('/') + 1)];
                    string stage = parent + ".nasmount-stage-" + handle.Id;
                    handle.Progressive = new DirectWebDavProgressiveUpload(
                        _http, Remote(stage), stage, handle.LocalPath,
                        handle.PlannedCopySize);
                }
                catch (Exception error) when (error is IOException or
                    ArgumentException or HttpRequestException)
                {
                    handle.ProgressiveUnavailable = true;
                    Console.Error.WriteLine("DIRECT_PROGRESSIVE_NOT_AVAILABLE " +
                        error.GetType().Name);
                }
            }
            if (handle.Progressive is { } active)
            {
                if (!active.Publish(position, data.Length))
                {
                    handle.AbandonedStages.Add(active.StagePath);
                    active.Cancel();
                    handle.Progressive = null;
                    handle.ProgressiveUnavailable = true;
                }
                else
                {
                    upload = active;
                    waitFor = position + data.Length;
                }
            }
        }
        // Back-pressure must never hold the local journal lock while the
        // network task reads it.
        if (upload is not null &&
            !upload.WaitForSent(waitFor, TimeSpan.FromSeconds(25)))
        {
            lock (handle.Gate)
            {
                if (ReferenceEquals(upload, handle.Progressive))
                {
                    handle.AbandonedStages.Add(upload.StagePath);
                    upload.Cancel();
                    handle.Progressive = null;
                    handle.ProgressiveUnavailable = true;
                }
            }
        }
    }

    internal void SetLength(Handle handle, long size)
    {
        lock (handle.Gate)
        {
            if (handle.Progressive is not null &&
                handle.Local.Length != size)
            {
                handle.AbandonedStages.Add(handle.Progressive.StagePath);
                handle.Progressive.Cancel();
                handle.Progressive = null;
                handle.ProgressiveUnavailable = true;
            }
            handle.PlannedCopySize =
                handle.Created && handle.Local.Length == 0 &&
                size >= 2 * 1024 * 1024 &&
                !WriteIntent.IsDocument(handle.RemotePath) &&
                !handle.ProgressiveUnavailable
                    ? size : 0;
            if (handle.Created && handle.ContiguousWritten > 0 &&
                handle.Local.Length != size)
                handle.SequentialWrites = false;
            handle.Local.SetLength(size);
            handle.Dirty = true;
            SaveJournal(handle, "dirty");
        }
    }

    private void CleanupAbandonedStages(Handle handle)
    {
        foreach (string stage in handle.AbandonedStages)
        {
            try
            {
                using var request = new HttpRequestMessage(
                    HttpMethod.Delete, Remote(stage));
                using var result = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                if (!result.IsSuccessStatusCode &&
                    result.StatusCode != HttpStatusCode.NotFound)
                    Console.Error.WriteLine(
                        "DIRECT_PROGRESSIVE_STAGE_CLEANUP_PENDING status=" +
                        (int)result.StatusCode);
            }
            catch (HttpRequestException)
            {
                Console.Error.WriteLine(
                    "DIRECT_PROGRESSIVE_STAGE_CLEANUP_PENDING transport");
            }
        }
        handle.AbandonedStages.Clear();
    }

    // After crash recovery, remove the exact private stage for this journal.
    private void CleanupRecoveredStage(Handle handle)
    {
        string parent = handle.RemotePath[
            ..(handle.RemotePath.LastIndexOf('/') + 1)];
        string stage = parent + ".nasmount-stage-" + handle.Id;
        handle.AbandonedStages.Add(stage);
        CleanupAbandonedStages(handle);
    }

    private bool TryCommitProgressive(Handle handle)
    {
        var upload = handle.Progressive;
        if (upload is null) return false;
        handle.Progressive = null;
        bool moveAttempted = false;
        try
        {
            if (upload.Written != upload.Length ||
                handle.Local.Length != upload.Length)
                return false;
            upload.Complete();
            _read.Invalidate(upload.StagePath);
            var staged = _read.Stat(upload.StagePath);
            if (staged is null || staged.Size != handle.Local.Length ||
                !StrongEtag(staged.ETag))
                throw new IOException("Progressive stage size/ETag unverified");
            _read.Invalidate(handle.RemotePath);
            if (_read.Stat(handle.RemotePath) is not null)
                throw new IOException("Progressive destination already exists");
            // Existing WebDAV MOVE path sends Overwrite:F and If-Match.
            moveAttempted = true;
            RenameNewDestination(upload.StagePath, handle.RemotePath, staged.ETag);
            _read.Invalidate(handle.RemotePath);
            var final = _read.Stat(handle.RemotePath);
            if (final is null || final.Size != handle.Local.Length ||
                !StrongEtag(final.ETag))
                throw new IOException("Progressive final metadata unverified");
            handle.ExpectedEtag = final.ETag;
            handle.Created = false;
            handle.Dirty = false;
            handle.UploadMayHaveCommitted = false;
            SaveJournal(handle, "committed");
            Console.WriteLine("DIRECT_PROGRESSIVE_COPY_COMMIT_PASS bytes=" +
                handle.Local.Length);
            return true;
        }
        catch (Exception error) when (error is IOException or
            HttpRequestException or OperationCanceledException)
        {
            if (moveAttempted)
            {
                // An uncertain MOVE may still have reached the NAS.
                // Never follow it with an unverified overwrite.
                handle.UploadMayHaveCommitted = true;
                if (AlreadyCommittedRemotely(handle)) return true;
                SaveJournal(handle, "progressive-move-recovery-required");
                throw new IOException(
                    "Progressive rename outcome unverified; recovery retained",
                    error);
            }
            Console.WriteLine("DIRECT_PROGRESSIVE_FALLBACK reason=" +
                error.GetType().Name);
            return false;
        }
        finally
        {
            upload.Dispose();
            handle.AbandonedStages.Add(upload.StagePath);
            CleanupAbandonedStages(handle);
        }
    }

    internal void PlanCopy(Handle handle, long length)
    {
        lock (handle.Gate)
        {
            if (handle.Created && handle.Local.Length == 0 &&
                handle.Progressive is null && !handle.ProgressiveUnavailable &&
                length >= 2 * 1024 * 1024 &&
                !WriteIntent.IsDocument(handle.RemotePath))
                handle.PlannedCopySize = length;
        }
    }

    internal int Read(Handle handle, long offset, Span<byte> output)
    {
        lock (handle.Gate)
        {
            handle.Local.Seek(offset, SeekOrigin.Begin);
            return handle.Local.Read(output);
        }
    }

    internal void Commit(Handle handle)
    {
        RequireVerifiedConditionalWrites();
        lock (handle.Gate)
        {
            if (!handle.Dirty) return;
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            string path = handle.RemotePath;
            lock (_locks.GetOrAdd(path, _ => new object()))
            {
                if (handle.Progressive is not null &&
                    TryCommitProgressive(handle))
                    return;
                CleanupAbandonedStages(handle);
                if (handle.UploadMayHaveCommitted)
                {
                    // A previous Flush may have failed after the NAS PUT
                    // succeeded. Check the full remote body before sending
                    // the same update with an obsolete If-Match.
                    if (AlreadyCommittedRemotely(handle))
                    {
                        handle.UploadMayHaveCommitted = false;
                        return;
                    }
                    _read.Invalidate(path);
                    var latest = _read.Stat(path);
                    if (handle.Created ? latest is not null :
                        latest?.ETag != handle.ExpectedEtag)
                    {
                        SaveJournal(handle, "conflict-recovery-required");
                        throw new IOException(
                            "Previous upload outcome uncertain and NAS " +
                            "revision differs; recovery copy retained");
                    }
                }
                SaveJournal(handle, "uploading");
                using var request = new HttpRequestMessage(HttpMethod.Put, Remote(path));
                if (handle.Created)
                    request.Headers.TryAddWithoutValidation("If-None-Match", "*");
                else if (StrongEtag(handle.ExpectedEtag))
                    request.Headers.TryAddWithoutValidation("If-Match", handle.ExpectedEtag);
                else throw new IOException("Refusing unguarded WebDAV overwrite");
                handle.Local.Seek(0, SeekOrigin.Begin);
                request.Content = new StreamContent(new NonClosingStream(handle.Local));
                // A timeout while sending or validating cannot prove that
                // the server rejected the bytes. Future Flush/Close must
                // re-read the remote content rather than re-PUT blindly.
                handle.UploadMayHaveCommitted = true;
                using var result = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                if (result.StatusCode is HttpStatusCode.PreconditionFailed or
                    HttpStatusCode.Conflict or HttpStatusCode.Locked)
                {
                    if (AlreadyCommittedRemotely(handle))
                    {
                        handle.UploadMayHaveCommitted = false;
                        return;
                    }
                    SaveJournal(handle, "conflict");
                    throw new IOException("Concurrent NAS edit detected HTTP " +
                        (int)result.StatusCode + "; local recovery copy retained");
                }
                if (!result.IsSuccessStatusCode)
                {
                    SaveJournal(handle, "retry-needed");
                    throw new IOException("WebDAV upload failed HTTP " +
                        (int)result.StatusCode + "; local recovery copy retained");
                }
                DirectWebDavReadOnlyBackend.Entry? committed = null;
                // Some WebDAV servers expose the old PROPFIND revision for
                // a short interval after a successful PUT. Do not report
                // Excel's Flush as failed on the first stale metadata read.
                for (int attempt = 0; attempt < 5; attempt++)
                {
                    _read.Invalidate(path);
                    committed = _read.Stat(path);
                    if (committed is not null &&
                        committed.Size == handle.Local.Length &&
                        StrongEtag(committed.ETag)) break;
                    if (attempt < 4) Thread.Sleep(150 * (attempt + 1));
                }
                if (committed is null || committed.Size != handle.Local.Length ||
                    !StrongEtag(committed.ETag))
                {
                    SaveJournal(handle, "verification-needed");
                    Console.Error.WriteLine("DIRECT_WEBDAV_WRITE_VERIFY_METADATA_FAILED" +
                        " stage=POST_PUT_METADATA sizeMatches=" +
                        (committed?.Size == handle.Local.Length) +
                        " etagStrong=" + StrongEtag(committed?.ETag));
                    throw new IOException("WebDAV POST_PUT_METADATA verification failed; recovery retained");
                }
                if (handle.Local.Length <= 16 * 1024 * 1024)
                {
                    using var check = _http.GetAsync(Remote(path),
                        HttpCompletionOption.ResponseHeadersRead).GetAwaiter().GetResult();
                    check.EnsureSuccessStatusCode();
                    using var remoteContent = check.Content.ReadAsStream();
                    handle.Local.Seek(0, SeekOrigin.Begin);
                    byte[] localHash = SHA256.HashData(handle.Local);
                    byte[] remoteHash = SHA256.HashData(remoteContent);
                    if (!CryptographicOperations.FixedTimeEquals(localHash, remoteHash))
                    {
                        SaveJournal(handle, "verification-failed");
                        throw new IOException("NAS post-upload checksum mismatch; recovery retained");
                    }
                }
                handle.ExpectedEtag = committed.ETag;
                handle.Created = false;
                handle.Dirty = false;
                handle.UploadMayHaveCommitted = false;
                SaveJournal(handle, "committed");
            }
        }
    }

    internal void Close(Handle handle, bool commit)
    {
        if (handle.Closed) return;
        if (commit && handle.Dirty) Commit(handle);
        if (!commit && handle.Progressive is { } abandoned)
        {
            abandoned.Cancel();
            handle.AbandonedStages.Add(abandoned.StagePath);
            handle.Progressive = null;
        }
        CleanupAbandonedStages(handle);
        string id = handle.Id;
        bool complete = !handle.Dirty;
        handle.Dispose();
        // Cleanup is only allowed after actual verified server commit.
        if (complete)
        {
            File.Delete(ManifestPath(id));
            File.Delete(DataPath(id));
        }
    }

    internal void Discard(Handle handle)
    {
        // An uncommitted temporary created and then explicitly deleted by
        // Windows must not be uploaded or treated as a crash-recovery file.
        handle.Dirty = false;
        Close(handle, commit: false);
    }

    internal Journal[] PendingRecovery()
    {
        var pending = new List<Journal>();
        foreach (string path in Directory.EnumerateFiles(_journalRoot, "*.json"))
        {
            try
            {
                var entry = JsonSerializer.Deserialize<Journal>(File.ReadAllText(path));
                if (entry is not null && File.Exists(DataPath(entry.Id)))
                    pending.Add(entry);
            }
            catch (IOException) { }
            catch (JsonException) { }
        }
        return pending.ToArray();
    }

    internal bool HasUncommittedRecovery => PendingRecovery().Any(j =>
        j.Status is not ("opened" or "committed" or "recovered-verified"));

    private bool AlreadyCommittedRemotely(Handle handle)
    {
        // A PUT may have committed before a network failure lost its reply.
        // Compare the entire remote body before attempting another write.
        _read.Invalidate(handle.RemotePath);
        var current = _read.Stat(handle.RemotePath);
        if (current is null || current.IsDirectory ||
            current.Size != handle.Local.Length || !StrongEtag(current.ETag))
            return false;
        using var request = new HttpRequestMessage(HttpMethod.Get,
            Remote(handle.RemotePath));
        request.Headers.TryAddWithoutValidation("If-Match", current.ETag);
        using var response = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode) return false;
        using var remote = response.Content.ReadAsStream();
        handle.Local.Seek(0, SeekOrigin.Begin);
        byte[] localHash = SHA256.HashData(handle.Local);
        byte[] remoteHash = SHA256.HashData(remote);
        if (!CryptographicOperations.FixedTimeEquals(localHash, remoteHash))
            return false;
        _read.Invalidate(handle.RemotePath);
        if (_read.Stat(handle.RemotePath)?.ETag != current.ETag)
            return false;
        handle.ExpectedEtag = current.ETag;
        handle.Created = false;
        handle.Dirty = false;
        SaveJournal(handle, "recovered-verified");
        return true;
    }

    internal (int recovered, int conflicted, int retained) RecoverPending(
        LeaseCoordinator? officeLeases = null)
    {
        RequireVerifiedConditionalWrites();
        RequireExclusiveLockDirectory();
        int recovered = 0, conflicted = 0, retained = 0;
        foreach (var journal in PendingRecovery())
        {
            if (journal.Id.Length != 32 ||
                journal.Id.Any(c => !Uri.IsHexDigit(c)) ||
                DirectWebDavReadOnlyBackend.Normalize(journal.Path) != journal.Path ||
                journal.Path == "/" || journal.Size < 0)
            {
                retained++;
                continue;
            }
            // A preallocated, partly copied NEW file contains zero-filled
            // unwritten ranges. It is never safe to replay that as a
            // completed user file after a crash. Preserve its local
            // recovery copy for explicit inspection instead.
            if (journal.Created &&
                (!journal.SequentialWrites ||
                 journal.WrittenBytes != journal.Size))
            {
                Console.Error.WriteLine(
                    "DIRECT_RECOVERY_INCOMPLETE_COPY_RETAINED");
                retained++;
                continue;
            }
            string? leaseKey = null;
            try
            {
                if (WriteIntent.IsDocument(journal.Path))
                {
                    if (officeLeases is null) { retained++; continue; }
                    leaseKey = journal.Path.TrimStart('/');
                    officeLeases.AddHandle(leaseKey);
                }
                using var file = new FileStream(DataPath(journal.Id),
                    FileMode.Open, FileAccess.ReadWrite, FileShare.Read,
                    65536, FileOptions.RandomAccess | FileOptions.WriteThrough);
                if (file.Length != journal.Size) { retained++; continue; }
                using var handle = new Handle(this, journal.Id, DataPath(journal.Id),
                    journal.Path, journal.ExpectedEtag, journal.Created, file)
                    {
                        Dirty = journal.Status != "opened",
                        ContiguousWritten = journal.WrittenBytes,
                        SequentialWrites = journal.SequentialWrites
                    };
                bool complete = false;
                lock (_locks.GetOrAdd(journal.Path, _ => new object()))
                {
                    if (AlreadyCommittedRemotely(handle))
                        complete = true;
                    else if (!handle.Dirty)
                        retained++;
                    else
                    {
                        _read.Invalidate(journal.Path);
                        var remote = _read.Stat(journal.Path);
                        bool sameRevision = journal.Created ? remote is null :
                            remote?.ETag == journal.ExpectedEtag;
                        if (!sameRevision)
                        {
                            conflicted++;
                            SaveJournal(handle, "conflict-recovery-required");
                        }
                        else
                        {
                            Commit(handle);
                            complete = true;
                        }
                    }
                }
                if (complete)
                {
                    handle.Dispose();
                    CleanupRecoveredStage(handle);
                    File.Delete(ManifestPath(journal.Id));
                    File.Delete(DataPath(journal.Id));
                    recovered++;
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                Console.Error.WriteLine("DIRECT_RECOVERY_RETAINED path=" +
                    journal.Path + " error=" + ex.GetType().Name);
                retained++;
            }
            finally
            {
                if (leaseKey is not null) officeLeases?.ReleaseHandle(leaseKey);
            }
        }
        Console.WriteLine("DIRECT_WEBDAV_RECOVERY_SUMMARY recovered=" + recovered +
            " conflicts=" + conflicted + " retained=" + retained);
        return (recovered, conflicted, retained);
    }

    internal void MakeDirectory(string path)
    {
        using var request = new HttpRequestMessage(new HttpMethod("MKCOL"), Remote(path));
        using var result = _http.Send(request);
        if (result.StatusCode != HttpStatusCode.Created)
            throw new IOException("WebDAV MKCOL failed HTTP " + (int)result.StatusCode);
        _read.Invalidate(path);
    }

    internal void RenameDirectory(string from, string to, string? originalEtag,
        bool replaceIfExists)
    {
        from = DirectWebDavReadOnlyBackend.Normalize(from);
        to = DirectWebDavReadOnlyBackend.Normalize(to);
        if (from == "/" || to == "/" ||
            from.Equals(to, StringComparison.OrdinalIgnoreCase) ||
            to.StartsWith(from.TrimEnd('/') + "/", StringComparison.OrdinalIgnoreCase))
            throw new IOException("Invalid directory rename destination");

        // A WebDAV collection (folder) does not always have a strong ETag.
        // The FILE-specific Get/conditional-PUT/DELETE replacement path must
        // never be used for directories. Preserve the subtree atomically via
        // a MOVE with Overwrite:F; never replace another user's folder.
        var source = _read.Stat(from);
        if (source is null || !source.IsDirectory)
            throw new FileNotFoundException("Directory rename source not found");
        if (_read.Stat(to) is not null)
            throw new IOException("Destination already exists");
        if (replaceIfExists)
        {
            // Explorer may set this flag for a normal rename. Do not honor
            // it for folders: replacing an existing collection can lose data.
        }
        using var request = new HttpRequestMessage(new HttpMethod("MOVE"), Remote(from));
        request.Headers.TryAddWithoutValidation("Destination", Remote(to).AbsoluteUri);
        request.Headers.TryAddWithoutValidation("Overwrite", "F");
        if (StrongEtag(originalEtag))
            request.Headers.TryAddWithoutValidation("If-Match", originalEtag);
        using var result = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (result.StatusCode is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
            throw new IOException("WebDAV directory MOVE failed HTTP " +
                (int)result.StatusCode);
        _read.Invalidate(from);
        _read.Invalidate(to);
        for (int attempt = 0; attempt < 5; attempt++)
        {
            var renamed = _read.Stat(to);
            var previous = _read.Stat(from);
            if (renamed?.IsDirectory == true && previous is null)
            {
                Console.WriteLine("DIRECT_WEBDAV_DIRECTORY_MOVE_VERIFIED");
                return;
            }
            if (attempt < 4) Thread.Sleep(150 * (attempt + 1));
            _read.Invalidate(from);
            _read.Invalidate(to);
        }
        // The server accepted the MOVE, so never retry it automatically:
        // a delayed PROPFIND response does not prove it did not happen.
        throw new IOException("WebDAV directory MOVE accepted but destination verification pending");
    }

    internal void RenameNewDestination(string from, string to, string? originalEtag)
    {
        RequireVerifiedConditionalWrites();
        // Never use WebDAV MOVE Overwrite:T. Excel normally performs two
        // new-destination renames; replacing an existing original requires
        // a separately verified lock+conditional-commit transaction.
        if (!StrongEtag(originalEtag))
            throw new IOException("Unsafe rename without strong source ETag");
        using var request = new HttpRequestMessage(new HttpMethod("MOVE"), Remote(from));
        request.Headers.TryAddWithoutValidation("Destination", Remote(to).AbsoluteUri);
        request.Headers.TryAddWithoutValidation("Overwrite", "F");
        request.Headers.TryAddWithoutValidation("If-Match", originalEtag);
        using var result = _http.Send(request);
        if (result.StatusCode is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
            throw new IOException("Safe WebDAV MOVE failed HTTP " + (int)result.StatusCode);
        _read.Invalidate(from);
        _read.Invalidate(to);
    }

    // Office often renames a temporary file ON TOP OF an existing workbook.
    // WebDAV MOVE with Overwrite:F fails there; Overwrite:T is unsafe because
    // it can ignore a target revision changed by another NAS client.
    // A conditional PUT to the exact destination ETag, followed by byte
    // verification and conditional source deletion, preserves concurrency.
    internal void RenameWithVerifiedReplacement(string from, string to,
        string? sourceEtag, bool replaceIfExists)
    {
        RequireVerifiedConditionalWrites();
        if (!StrongEtag(sourceEtag))
            throw new IOException("Unsafe rename without strong source ETag");
        var destination = _read.Stat(to);
        if (destination is null)
        {
            RenameNewDestination(from, to, sourceEtag);
            return;
        }
        if (!replaceIfExists)
            throw new IOException("Destination already exists");
        if (destination.IsDirectory || !StrongEtag(destination.ETag))
            throw new IOException("Cannot safely replace destination without strong ETag");

        string stage = Path.Combine(_journalRoot,
            "replace-" + Guid.NewGuid().ToString("N") + ".partial");
        try
        {
            using (var get = new HttpRequestMessage(HttpMethod.Get, Remote(from)))
            {
                get.Headers.TryAddWithoutValidation("If-Match", sourceEtag);
                using var response = _http.Send(get, HttpCompletionOption.ResponseHeadersRead);
                if (!response.IsSuccessStatusCode)
                    throw new IOException("Safe replacement source GET HTTP " +
                        (int)response.StatusCode);
                using var input = response.Content.ReadAsStream();
                using var local = new FileStream(stage, FileMode.CreateNew,
                    FileAccess.ReadWrite, FileShare.None);
                input.CopyTo(local);
                local.Flush(true);
            }
            _read.Invalidate(from);
            var source = _read.Stat(from);
            if (source?.ETag != sourceEtag || source.Size != new FileInfo(stage).Length)
                throw new IOException("Source changed during conditional replacement");

            using (var put = new HttpRequestMessage(HttpMethod.Put, Remote(to)))
            {
                put.Headers.TryAddWithoutValidation("If-Match", destination.ETag);
                put.Content = new StreamContent(new FileStream(stage, FileMode.Open,
                    FileAccess.Read, FileShare.Read));
                using var result = _http.Send(put, HttpCompletionOption.ResponseHeadersRead);
                if (!result.IsSuccessStatusCode)
                    throw new IOException("Safe replacement target PUT HTTP " +
                        (int)result.StatusCode);
            }
            _read.Invalidate(to);
            var saved = _read.Stat(to);
            if (saved is null || saved.Size != new FileInfo(stage).Length ||
                !StrongEtag(saved.ETag))
                throw new IOException("Cannot verify replacement metadata; source retained");
            using (var request = new HttpRequestMessage(HttpMethod.Get, Remote(to)))
            {
                request.Headers.TryAddWithoutValidation("If-Match", saved.ETag);
                using var verified = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                verified.EnsureSuccessStatusCode();
                using var remote = verified.Content.ReadAsStream();
                using var local = File.OpenRead(stage);
                if (!CryptographicOperations.FixedTimeEquals(
                        SHA256.HashData(local), SHA256.HashData(remote)))
                    throw new IOException("Replacement checksum mismatch; source retained");
            }
            Delete(from, sourceEtag);
            Console.WriteLine("DIRECT_WEBDAV_EXCEL_REPLACEMENT_VERIFIED");
        }
        finally { try { File.Delete(stage); } catch (IOException) { } }
    }

    internal void Delete(string path, string? etag)
    {
        RequireVerifiedConditionalWrites();
        if (!StrongEtag(etag))
            throw new IOException("Refusing DELETE without strong source ETag");
        using var request = new HttpRequestMessage(HttpMethod.Delete, Remote(path));
        request.Headers.TryAddWithoutValidation("If-Match", etag);
        using var result = _http.Send(request);
        if (!result.IsSuccessStatusCode)
            throw new IOException("WebDAV DELETE rejected HTTP " + (int)result.StatusCode);
        _read.Invalidate(path);
    }

    public void Dispose() => _http.Dispose();

    internal static void IntegrationTest(Uri server, string recoveryRoot)
    {
        using var read = new DirectWebDavReadOnlyBackend(server);
        using var write = new DirectWebDavWriteStore(server, read, recoveryRoot);
        string folder = "FileHubDirectLab_" + Guid.NewGuid().ToString("N");
        string workbook = "/" + folder + "/11wol.xlsx";
        string output = "/" + folder + "/11wol-replaced.xlsx";
        write.MakeDirectory(folder);
        try
        {
            for (int round = 1; round <= 3; round++)
            {
                using var h = write.Begin(workbook, create: round == 1);
                byte[] value = Encoding.UTF8.GetBytes("Excel repeated save " + round);
                write.SetLength(h, 0);
                write.Write(h, 0, value);
                write.Commit(h);
                write.Close(h, commit: true);
                using var verify = new HttpClient();
                byte[] actual = verify.GetByteArrayAsync(write.Remote(workbook))
                    .GetAwaiter().GetResult();
                if (!actual.SequenceEqual(value))
                    throw new IOException("Repeated save did not persist exact content " + round);
                Console.WriteLine("DIRECT_WEBDAV_REPEAT_SAVE_ROUND_" + round + "_PASS");
            }

            // Reproduce a Synology save where PUT succeeded but Flush
            // verification/response was lost. The old code retried the same
            // If-Match on Close, received HTTP 412, and reported failure
            // despite the desired bytes already being present on the NAS.
            using (var h = write.Begin(workbook, create: false))
            {
                byte[] updated = Encoding.UTF8.GetBytes(
                    "Verified bytes after lost flush response");
                write.SetLength(h, 0);
                write.Write(h, 0, updated);
                string originalTag = h.ExpectedEtag ??
                    throw new IOException("No original ETag for retry test");
                using (var remote = new HttpClient())
                using (var put = new HttpRequestMessage(HttpMethod.Put,
                    write.Remote(workbook)))
                {
                    put.Headers.TryAddWithoutValidation("If-Match", originalTag);
                    put.Content = new ByteArrayContent(updated);
                    using var result = remote.Send(put);
                    result.EnsureSuccessStatusCode();
                }
                h.UploadMayHaveCommitted = true; // Simulate lost confirmation.
                write.Commit(h);
                if (h.Dirty || h.UploadMayHaveCommitted)
                    throw new IOException("Committed PUT was not reconciled");
                write.Close(h, commit: true);
                using var inspectionClient = new HttpClient();
                if (!inspectionClient.GetByteArrayAsync(write.Remote(workbook))
                    .GetAwaiter().GetResult().SequenceEqual(updated))
                    throw new IOException("Reconciliation modified NAS bytes");
                Console.WriteLine(
                    "DIRECT_WEBDAV_LOST_FLUSH_412_RECONCILED_NO_REUPLOAD_PASS");
            }

            using (var h = write.Begin(workbook, create: false))
            {
                write.Write(h, 0, Encoding.UTF8.GetBytes("local version"));
                using var outsider = new HttpClient();
                using var external = outsider.PutAsync(write.Remote(workbook),
                    new ByteArrayContent(Encoding.UTF8.GetBytes("other PC revision")))
                    .GetAwaiter().GetResult();
                external.EnsureSuccessStatusCode();
                bool blocked = false;
                try { write.Commit(h); }
                catch (IOException) { blocked = true; }
                if (!blocked)
                    throw new IOException("Remote concurrent modification was overwritten");
                byte[] remote = outsider.GetByteArrayAsync(write.Remote(workbook))
                    .GetAwaiter().GetResult();
                if (Encoding.UTF8.GetString(remote) != "other PC revision")
                    throw new IOException("Conflicting write corrupted remote file");
                if (!write.PendingRecovery().Any(e => e.Id == h.Id))
                    throw new IOException("Conflict recovery copy was lost");
                write.Close(h, commit: false);
                Console.WriteLine("DIRECT_WEBDAV_CONCURRENT_EDIT_BLOCKED_LOCAL_COPY_RETAINED_PASS");
            }
            read.Invalidate(workbook);
            using (var h = write.Begin(workbook, create: false))
            {
                write.SetLength(h, 0);
                write.Write(h, 0, Encoding.UTF8.GetBytes("resolved after other PC"));
                write.Commit(h);
                write.Close(h, commit: true);
            }

            var existing = read.Stat(workbook) ?? throw new IOException("Workbook vanished");
            write.RenameNewDestination(workbook, output, existing.ETag);
            if (read.Stat(workbook) is not null || read.Stat(output) is null)
                throw new IOException("Atomic MOVE to new destination failed");
            Console.WriteLine("DIRECT_WEBDAV_EXCEL_BACKUP_SAFE_MOVE_PASS");

            var current = read.Stat(output)!;
            write.Delete(output, current.ETag);
            if (read.Stat(output) is not null)
                throw new IOException("Conditional delete failed");
            Console.WriteLine("DIRECT_WEBDAV_ETAG_CONDITIONAL_DELETE_PASS");

            string crashed = "/" + folder + "/crashed-edit.txt";
            string crashId;
            using (var h = write.Begin(crashed, create: true))
            {
                crashId = h.Id;
                write.Write(h, 0, Encoding.UTF8.GetBytes("offline bytes survived"));
                // Simulate process death: no Flush, Commit or Close callback.
            }
            if (!write.PendingRecovery().Any(x => x.Id == crashId))
                throw new IOException("Simulated crash lost durable local journal");
            // A new store instance simulates restarting FileHub after a
            // process crash. No in-memory session/cache data is reused.
            using var restartRead = new DirectWebDavReadOnlyBackend(server);
            using var restart = new DirectWebDavWriteStore(server, restartRead,
                recoveryRoot);
            var recovery = restart.RecoverPending();
            using (var outsider = new HttpClient())
            {
                string serverContent = outsider.GetStringAsync(write.Remote(crashed))
                    .GetAwaiter().GetResult();
                if (serverContent != "offline bytes survived" || recovery.recovered < 1 ||
                    write.PendingRecovery().Any(x => x.Id == crashId))
                    throw new IOException("Interrupted file was not recovered safely");
            }
            Console.WriteLine("DIRECT_WEBDAV_CRASH_JOURNAL_REPLAY_AND_CHECKSUM_PASS");

            string conflicted = "/" + folder + "/crash-conflict.txt";
            using (var original = write.Begin(conflicted, create: true))
            {
                write.Write(original, 0, Encoding.UTF8.GetBytes("original remote"));
                write.Commit(original);
                write.Close(original, commit: true);
            }
            string conflictId;
            using (var pending = write.Begin(conflicted, create: false))
            {
                conflictId = pending.Id;
                write.SetLength(pending, 0);
                write.Write(pending, 0, Encoding.UTF8.GetBytes("local unsent update"));
            }
            using (var outsider = new HttpClient())
            {
                using var reply = outsider.PutAsync(write.Remote(conflicted),
                    new StringContent("remote changed while crashed"))
                    .GetAwaiter().GetResult();
                reply.EnsureSuccessStatusCode();
                recovery = restart.RecoverPending();
                string remote = outsider.GetStringAsync(write.Remote(conflicted))
                    .GetAwaiter().GetResult();
                if (remote != "remote changed while crashed" ||
                    recovery.conflicted < 1 ||
                    !write.PendingRecovery().Any(x => x.Id == conflictId))
                    throw new IOException("Crash-replay overwrote another PC edit");
            }
            Console.WriteLine("DIRECT_WEBDAV_CRASH_REPLAY_CONFLICT_FAIL_CLOSED_PASS");
        }
        finally
        {
            using var http = new HttpClient();
            try { http.DeleteAsync(write.Remote(folder)).GetAwaiter().GetResult().Dispose(); }
            catch (HttpRequestException) { }
        }
        Console.WriteLine("DIRECT_WEBDAV_ONE_WINFSP_WRITE_STORE_LAB_PASS");
    }

    internal static void PolicyTest()
    {
        if (!StrongEtag("\"abc\"") || StrongEtag("W/\"abc\"") ||
            StrongEtag(null) || StrongEtag(""))
            throw new InvalidOperationException("Write store ETag safety filter failed");
        Console.WriteLine("DIRECT_WEBDAV_WRITE_ETAG_FAIL_CLOSED_POLICY_PASS");
    }
}
