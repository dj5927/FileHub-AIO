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
        bool Created, DateTime CreatedUtc, long Size, string Status);

    internal sealed class Handle : IDisposable
    {
        internal readonly DirectWebDavWriteStore Owner;
        internal readonly string Id;
        internal readonly string LocalPath;
        internal string RemotePath;
        internal string? ExpectedEtag;
        internal bool Created;
        internal bool Dirty;
        internal bool Closed;
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
    private readonly DirectWebDavReadOnlyBackend _read;
    private readonly ConcurrentDictionary<string, object> _locks =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly object _capabilityGate = new();
    private bool _conditionalWritesVerified;
    private bool _exclusiveMkcolVerified;

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
    internal void RequireVerifiedConditionalWrites()
    {
        lock (_capabilityGate)
        {
            if (_conditionalWritesVerified) return;
            string name = "/.filehub-conditional-probe-" + Guid.NewGuid().ToString("N");
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
                        throw new IOException("Conditional PUT not supported: create failed");
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

    internal void RequireExclusiveLockDirectory()
    {
        lock (_capabilityGate)
        {
            if (_exclusiveMkcolVerified) return;
            string name = "/.filehub-mkcol-probe-" + Guid.NewGuid().ToString("N");
            Uri url = Remote(name);
            try
            {
                using (var first = new HttpRequestMessage(new HttpMethod("MKCOL"), url))
                using (var response = _http.Send(first))
                    if (response.StatusCode != HttpStatusCode.Created)
                        throw new IOException("Server did not create exclusive WebDAV lock directory");
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
            h.Created, DateTime.UtcNow, h.Local.Length, status);
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
        lock (handle.Gate)
        {
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            if (offset > long.MaxValue) throw new IOException("File offset overflow");
            handle.Local.Seek(append ? 0 : (long)offset,
                append ? SeekOrigin.End : SeekOrigin.Begin);
            handle.Local.Write(data);
            handle.Dirty = true;
            SaveJournal(handle, "dirty");
        }
    }

    internal void SetLength(Handle handle, long size)
    {
        lock (handle.Gate)
        {
            handle.Local.SetLength(size);
            handle.Dirty = true;
            SaveJournal(handle, "dirty");
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
                SaveJournal(handle, "uploading");
                using var request = new HttpRequestMessage(HttpMethod.Put, Remote(path));
                if (handle.Created)
                    request.Headers.TryAddWithoutValidation("If-None-Match", "*");
                else if (StrongEtag(handle.ExpectedEtag))
                    request.Headers.TryAddWithoutValidation("If-Match", handle.ExpectedEtag);
                else throw new IOException("Refusing unguarded WebDAV overwrite");
                handle.Local.Seek(0, SeekOrigin.Begin);
                request.Content = new StreamContent(new NonClosingStream(handle.Local));
                using var result = _http.Send(request,
                    HttpCompletionOption.ResponseHeadersRead);
                if (result.StatusCode is HttpStatusCode.PreconditionFailed or
                    HttpStatusCode.Conflict or HttpStatusCode.Locked)
                {
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
                _read.Invalidate(path);
                var committed = _read.Stat(path);
                if (committed is null || committed.Size != handle.Local.Length ||
                    !StrongEtag(committed.ETag))
                {
                    SaveJournal(handle, "verification-needed");
                    throw new IOException("Cannot prove NAS accepted exact size/ETag; recovery retained");
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
                SaveJournal(handle, "committed");
            }
        }
    }

    internal void Close(Handle handle, bool commit)
    {
        if (handle.Closed) return;
        if (commit && handle.Dirty) Commit(handle);
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
                    { Dirty = journal.Status != "opened" };
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
