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
        // Only an exclusively created owner file, or a reopened owner
        // verified as belonging to this mount, may use the no-ETag path.
        internal byte[]? PublishedOfficeOwner;
        // Only Office files with verified local ownership may use an
        // advisory owner + byte-revision guard when a NAS has no ETag.
        internal byte[]? UnversionedBaseHash;
        // A successful progressive MOVE has already refreshed this metadata.
        // Reusing it avoids another slow network PROPFIND from WinFsp Flush.
        internal DirectWebDavReadOnlyBackend.Entry? CommittedEntry;
        internal bool Created;
        internal bool Dirty;
        // A previous PUT may already have reached the NAS even when the
        // follow-up verification failed. Do not retry with a stale ETag.
        internal bool UploadMayHaveCommitted;
        internal bool Closed;
        internal long ConfirmedSize;
        internal bool WrotePayload;
        internal string? OfficeSafetyBackup;
        internal long ContiguousWritten;
        internal bool SequentialWrites = true;
        // New-file journals are already marked uncommitted at Begin. For an
        // existing file the first write must mark recovery dirty immediately;
        // subsequent writes can checkpoint in batches rather than forcing a
        // durable JSON rewrite and full fsync for every Explorer copy block.
        internal bool JournalDirtyRecorded;
        internal long JournalCheckpointBytes;
        // An ordinary new-file write is staged locally and can be published
        // by the bounded asynchronous uploader after Explorer closes it.
        internal bool WriteBehind;
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
            ConfirmedSize = created ? 0 : stream.Length;
            // The initial "new-uncommitted" manifest covers zero bytes.
            // The first actual Write must checkpoint its updated length so
            // a complete small file remains recoverable after a crash.
            JournalDirtyRecorded = false;
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
    private readonly ConcurrentDictionary<string, byte[]> _publishedOfficeTemps =
        new(StringComparer.OrdinalIgnoreCase);
    internal Func<string, bool>? OwnsExcelWorkbook { get; set; }
    private readonly object _capabilityGate = new();
    private bool _conditionalWritesVerified;
    private bool _exclusiveMkcolVerified;
    private readonly HashSet<string> _safeStageMoveDirectories =
        new(StringComparer.OrdinalIgnoreCase);
    // Stop repeated Synology stage-upload failures for this mount.
    private int _progressiveUnsupported;

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
        // Bulk-copy acknowledgement is an OS write-cache operation, not a
        // remote commit. Avoid an unconditional fsync per 1 KB file. The
        // background uploader flushes the stage durably before its PUT.
        bool bufferedStage = h.WriteBehind &&
            (status is "new-uncommitted" or "queued");
        h.Local.Flush(!bufferedStage);
        var journal = new Journal(h.Id, h.RemotePath, h.ExpectedEtag,
            h.Created, DateTime.UtcNow, h.Local.Length, status,
            h.ContiguousWritten, h.SequentialWrites);
        string final = ManifestPath(h.Id);
        string temporary = final + ".pending";
        using (var f = new FileStream(temporary, FileMode.Create,
            FileAccess.Write, FileShare.None, 4096,
            bufferedStage ? FileOptions.None : FileOptions.WriteThrough))
        {
            JsonSerializer.Serialize(f, journal);
            f.Flush(!bufferedStage);
        }
        File.Move(temporary, final, true);
    }

    internal Handle Begin(string path, bool create, bool fastCopy = false,
        byte[]? expectedOfficeOwnerHash = null, bool allowExcelSnapshot = false)
    {
        RequireVerifiedConditionalWrites();
        string name = DirectWebDavReadOnlyBackend.Normalize(path);
        var current = create ? _read.KnownEntry(name) : _read.Stat(name);
        if (create && current is not null)
            throw new IOException("File already exists: " + name);
        if (!create && current is null)
            throw new FileNotFoundException("Existing WebDAV file missing", name);
        if (current?.IsDirectory == true)
            throw new IOException("Cannot edit directory as file");
        DirectWebDavReadOnlyBackend.OfficeOwnerSnapshot? ownMarker = null;
        if (!create && ExcelOwnerInterop.IsOwnerFile(name) &&
            expectedOfficeOwnerHash is not null)
        {
            ownMarker = _read.ReadOfficeOwnerFresh(name);
            if (ownMarker?.Bytes is not { } data ||
                !CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(data), expectedOfficeOwnerHash))
                throw new CooperativeLocks.LockConflictException(
                    "Excel owner changed since this mount published it");
            current = ownMarker.Entry;
        }
        byte[]? tempHash = null;
        bool missingValidator = !create && ownMarker is null && !StrongEtag(current!.ETag);
        bool ownedTemp = missingValidator && IsOfficeSaveTemporary(name) &&
            _publishedOfficeTemps.TryGetValue(name, out tempHash);
        // Excel opens an existing SaveAs target before creating its ~$ file.
        // Permit downloading/staging it after the filesystem's local lease
        // and foreign-owner check. All remote mutations still require the
        // verified owner (or the exclusively created empty placeholder).
        bool workbookSnapshot = missingValidator && ExcelOwnerInterop.IsWorkbook(name) &&
            allowExcelSnapshot && OwnsExcelWorkbook is not null;
        if (missingValidator && !ownedTemp && !workbookSnapshot)
            throw new IOException("Cannot safely edit without strong server ETag");

        string id = Guid.NewGuid().ToString("N");
        string local = DataPath(id);
        var stream = new FileStream(local, FileMode.CreateNew,
            FileAccess.ReadWrite, FileShare.Read | FileShare.Delete,
            65536, FileOptions.RandomAccess);
        try
        {
            if (ownMarker?.Bytes is { } previousOwner)
            {
                stream.Write(previousOwner);
                stream.Seek(0, SeekOrigin.Begin);
            }
            else if (!create)
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, Remote(name));
                if (!missingValidator)
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
                if (ownedTemp && !CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(stream), tempHash!))
                    throw new IOException("Office temporary source changed since upload");
                if (StrongEtag(response.Headers.ETag?.ToString()))
                    current = current with { ETag = response.Headers.ETag!.ToString() };
                stream.Seek(0, SeekOrigin.Begin);
            }
            var handle = new Handle(this, id, local, name,
                current?.ETag, create, stream);
            handle.PublishedOfficeOwner = ownMarker?.Bytes;
            if (missingValidator && !StrongEtag(current?.ETag))
            {
                handle.UnversionedBaseHash = SHA256.HashData(stream);
                stream.Seek(0, SeekOrigin.Begin);
                _publishedOfficeTemps[name] = handle.UnversionedBaseHash;
                Console.WriteLine("DIRECT_WEBDAV_OWNED_OFFICE_REOPEN_WITHOUT_ETAG");
            }
            handle.WriteBehind = fastCopy && create && !RequiresSynchronousSave(name);
            // For fast-copy handles the initial local file is incomplete;
            // a crash here must not replay partial data. Create its
            // resumable journal only when the copy is fully staged.
            if (!handle.WriteBehind)
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

    // Office saves a NEW workbook by writing/truncating a random eight-hex
    // filename, then moving it to the document name. Other Office apps use
    // .tmp and ~$ names. These are not ordinary sequential Explorer copies.
    internal static bool IsOfficeSaveTemporary(string path)
    {
        string name = Path.GetFileName(path);
        return name.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase) ||
            (name.Length == 8 && name.All(Uri.IsHexDigit));
    }

    internal static bool RequiresSynchronousSave(string path) =>
        WriteIntent.IsDocument(path) || ExcelOwnerInterop.IsOwnerFile(path) ||
        Path.GetFileName(path).StartsWith("~$", StringComparison.Ordinal) ||
        IsOfficeSaveTemporary(path);

    // Never put private names or server addresses in the normal engine log.
    internal static string OfficeTrace(string path) => Convert.ToHexString(
        SHA256.HashData(Encoding.UTF8.GetBytes(
            Path.GetFileName(path.Replace('/', '\\')).Normalize().ToUpperInvariant())))[..16];

    private static bool OfficePayload(string path) =>
        ExcelOwnerInterop.IsWorkbook(path) || IsOfficeSaveTemporary(path);

    private void RejectEmptyCommit(Handle handle)
    {
        if (!ExcelOwnerInterop.IsWorkbook(handle.RemotePath) || handle.Local.Length != 0 ||
            (handle.ConfirmedSize <= 0 && !handle.WrotePayload)) return;
        SaveJournal(handle, "office-empty-blocked");
        Console.WriteLine("DIRECT_WEBDAV_OFFICE_EMPTY_BLOCKED operation=commit file=" +
            OfficeTrace(handle.RemotePath) + " priorBytes=" + handle.ConfirmedSize +
            " journal=" + handle.Id);
        throw new IOException("Office save has no payload; original was not overwritten; recovery retained");
    }

    private void RecordConfirmedPayload(Handle handle)
    {
        handle.ConfirmedSize = handle.Local.Length;
        handle.WrotePayload = false;
        if (handle.ConfirmedSize > 0)
        {
            RetireOfficeSafetyCopy(handle.OfficeSafetyBackup);
            handle.OfficeSafetyBackup = null;
            if (ExcelOwnerInterop.IsWorkbook(handle.RemotePath))
                RetireOfficeRenameBackup(handle.RemotePath);
        }
    }

    private readonly ConcurrentDictionary<string, string> _officeRenameBackups =
        new(StringComparer.OrdinalIgnoreCase);

    // Keep the original revision outside the auto-replayed journal files.
    private string SaveOfficeSafetyCopy(string path, Stream original, long length)
    {
        if (length <= 0) throw new IOException("Cannot back up an empty workbook");
        string directory = Path.Combine(_journalRoot, "office-safety");
        Directory.CreateDirectory(directory);
        string id = Guid.NewGuid().ToString("N");
        string backup = Path.Combine(directory, id + Path.GetExtension(path));
        using (var file = new FileStream(backup, FileMode.CreateNew, FileAccess.ReadWrite,
            FileShare.Read, 65536, FileOptions.WriteThrough))
        {
            original.CopyTo(file);
            file.Flush(true);
            if (file.Length != length)
                throw new IOException("Workbook safety copy length mismatch");
            file.Position = 0;
            string hash = Convert.ToHexString(SHA256.HashData(file));
            using var manifest = new FileStream(backup + ".json", FileMode.CreateNew,
                FileAccess.Write, FileShare.Read, 4096, FileOptions.WriteThrough);
            JsonSerializer.Serialize(manifest, new { Path = path, Size = length,
                Sha256 = hash, CreatedUtc = DateTime.UtcNow });
            manifest.Flush(true);
        }
        Console.WriteLine("DIRECT_WEBDAV_OFFICE_SAFETY_BACKUP file=" + OfficeTrace(path) +
            " bytes=" + length + " backup=" + id);
        return backup;
    }

    private void PreserveBeforeMutation(Handle handle)
    {
        if (!ExcelOwnerInterop.IsWorkbook(handle.RemotePath) || handle.Created ||
            handle.Dirty || handle.ConfirmedSize <= 0 || handle.OfficeSafetyBackup is not null)
            return;
        long position = handle.Local.Position;
        try
        {
            handle.Local.Position = 0;
            handle.OfficeSafetyBackup = SaveOfficeSafetyCopy(handle.RemotePath, handle.Local,
                handle.ConfirmedSize);
        }
        finally { handle.Local.Position = position; }
    }

    private static void RetireOfficeSafetyCopy(string? path)
    {
        if (path is null) return;
        try { File.Delete(path); File.Delete(path + ".json"); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private void PreserveOfficeRenameBackup(string path,
        DirectWebDavReadOnlyBackend.Entry source)
    {
        if (!ExcelOwnerInterop.IsWorkbook(path) || source.Size <= 0 ||
            _officeRenameBackups.ContainsKey(path)) return;
        using var request = new HttpRequestMessage(HttpMethod.Get, Remote(path));
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        if (StrongEtag(source.ETag))
            request.Headers.TryAddWithoutValidation("If-Match", source.ETag);
        using var result = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (!result.IsSuccessStatusCode)
            throw new IOException("Workbook safety backup GET HTTP " + (int)result.StatusCode);
        string backup = SaveOfficeSafetyCopy(path, result.Content.ReadAsStream(), source.Size);
        if (!StrongEtag(source.ETag) &&
            _publishedOfficeTemps.TryGetValue(path, out var expected))
        {
            using var saved = File.OpenRead(backup);
            if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(saved), expected))
                throw new IOException("Workbook changed before safety backup; recovery retained");
        }
        if (!_officeRenameBackups.TryAdd(path, backup))
            RetireOfficeSafetyCopy(backup);
    }

    private void RetireOfficeRenameBackup(string workbook)
    {
        if (_officeRenameBackups.TryRemove(workbook, out var backup))
            RetireOfficeSafetyCopy(backup);
    }

    internal void Write(Handle handle, ulong offset, ReadOnlySpan<byte> data,
        bool append = false)
    {
        DirectWebDavProgressiveUpload? upload = null;
        long waitFor = 0;
        lock (handle.Gate)
        {
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            if (offset > long.MaxValue) throw new IOException("File offset overflow");
            if (!data.IsEmpty) PreserveBeforeMutation(handle);
            long position = append ? handle.Local.Length : (long)offset;
            handle.Local.Seek(append ? 0 : (long)offset,
                append ? SeekOrigin.End : SeekOrigin.Begin);
            handle.Local.Write(data);
            if (!data.IsEmpty) handle.WrotePayload = true;
            handle.Dirty = true;
            if (handle.Created)
            {
                if (handle.SequentialWrites && position == handle.ContiguousWritten)
                    handle.ContiguousWritten += data.Length;
                else if (data.Length != 0)
                    handle.SequentialWrites = false;
            }
            // The recovery manifest tracks only fully synced checkpoints.
            // If a copy is interrupted between checkpoints, recovery safely
            // retains the partial staging file instead of replaying it.
            if (!handle.WriteBehind &&
                (!handle.JournalDirtyRecorded ||
                handle.ContiguousWritten - handle.JournalCheckpointBytes >= 1024 * 1024))
            {
                SaveJournal(handle, "dirty");
                handle.JournalDirtyRecorded = true;
                handle.JournalCheckpointBytes = handle.ContiguousWritten;
            }
            // A known allocation alone is not enough to enable streaming.
            // Only a new, sequential, fixed-size non-Office file qualifies.
            if (!handle.WriteBehind && handle.Progressive is null &&
                !handle.ProgressiveUnavailable &&
                Volatile.Read(ref _progressiveUnsupported) == 0 &&
                handle.Created &&
                handle.PlannedCopySize >= 2 * 1024 * 1024 &&
                position == 0 && !append && data.Length > 0 &&
                !RequiresSynchronousSave(handle.RemotePath))
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
            if (Interlocked.Exchange(ref _progressiveUnsupported, 1) == 0)
                Console.WriteLine(
                    "DIRECT_PROGRESSIVE_DISABLED_FOR_MOUNT reason=stage_not_sent");
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
            if (handle.Local.Length != size) PreserveBeforeMutation(handle);
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
                !RequiresSynchronousSave(handle.RemotePath) &&
                !handle.ProgressiveUnavailable
                    ? size : 0;
            if (handle.Created && handle.ContiguousWritten > 0 &&
                handle.Local.Length != size)
                handle.SequentialWrites = false;
            handle.Local.SetLength(size);
            handle.Dirty = true;
            if (!handle.WriteBehind)
            {
                SaveJournal(handle, "dirty");
                handle.JournalDirtyRecorded = true;
                handle.JournalCheckpointBytes = handle.ContiguousWritten;
            }
        }
    }

    // Copying into the OS cache is NOT a NAS commit. A queued file is
    // written to local staging; the uploader fsyncs it before the PUT.
    // A sudden machine power loss before that background flush can lose it.
    internal bool QueueNewFile(Handle handle)
    {
        lock (handle.Gate)
        {
            if (handle.Closed || !handle.Created || !handle.WriteBehind ||
                RequiresSynchronousSave(handle.RemotePath) ||
                !handle.SequentialWrites ||
                handle.ContiguousWritten != handle.Local.Length)
                return false;
            SaveJournal(handle, "queued");
            handle.JournalDirtyRecorded = true;
            handle.JournalCheckpointBytes = handle.ContiguousWritten;
            return true;
        }
    }

    internal void FlushStaging(Handle handle)
    {
        lock (handle.Gate)
        {
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            handle.Local.Flush(false);
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
        bool moveVerified = false;
        var timer = System.Diagnostics.Stopwatch.StartNew();
        long ackMs = 0, stageMs = 0, moveMs = 0, finalMs = 0;
        try
        {
            if (upload.Written != upload.Length ||
                handle.Local.Length != upload.Length)
                return false;
            upload.Complete();
            ackMs = timer.ElapsedMilliseconds;
            string? stageEtag = upload.PutResponseEtag;
            if (!StrongEtag(stageEtag))
            {
                // A valid HTTP 2xx PUT with a known Content-Length confirms
                // the server accepted the full body. Obtain a strong source
                // revision only if PUT did not already return one.
                _read.Invalidate(upload.StagePath);
                var staged = _read.Stat(upload.StagePath);
                if (staged is null || staged.Size != upload.Length ||
                    !StrongEtag(staged.ETag))
                    throw new IOException("Progressive stage size/ETag unverified");
                stageEtag = staged.ETag;
            }
            stageMs = timer.ElapsedMilliseconds - ackMs;
            // Begin(create:true) already rejected an existing destination,
            // and the per-directory MOVE probe verified that Overwrite:F
            // rejects a concurrently-created target. A second remote Stat
            // right before MOVE adds latency but cannot prevent a race.
            moveAttempted = true;
            string? movedEtag = RenameNewDestination(
                upload.StagePath, handle.RemotePath, stageEtag);
            moveMs = timer.ElapsedMilliseconds - ackMs - stageMs;
            // A strong revision returned by the successful MOVE is the
            // server's own confirmation of the NEW destination. As the
            // source PUT was complete and MOVE is preflight-verified to
            // preserve bytes, no extra PROPFIND is necessary in this case.
            // Servers omitting a strong MOVE ETag keep the original
            // final-metadata verification path.
            var final = StrongEtag(movedEtag)
                ? new DirectWebDavReadOnlyBackend.Entry(
                    handle.RemotePath,
                    handle.RemotePath.Split('/').Last(),
                    false, handle.Local.Length, DateTime.UtcNow, movedEtag)
                : _read.Stat(handle.RemotePath);
            if (final is null || final.Size != handle.Local.Length ||
                !StrongEtag(final.ETag))
                throw new IOException("Progressive final metadata unverified");
            finalMs = timer.ElapsedMilliseconds - ackMs - stageMs - moveMs;
            moveVerified = true;
            handle.ExpectedEtag = final.ETag;
            handle.CommittedEntry = final;
            handle.Created = false;
            handle.Dirty = false;
            handle.UploadMayHaveCommitted = false;
            SaveJournal(handle, "committed");
            Console.WriteLine("DIRECT_PROGRESSIVE_COPY_COMMIT_PASS bytes=" +
                handle.Local.Length);
            Console.WriteLine("DIRECT_PROGRESSIVE_FINALIZE_MS ack=" + ackMs +
                " stage=" + stageMs + " move=" + moveMs +
                " final=" + finalMs + " total=" + timer.ElapsedMilliseconds);
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
            string reason = ProgressiveFailureCode(error);
            if (Interlocked.Exchange(ref _progressiveUnsupported, 1) == 0)
                Console.WriteLine(
                    "DIRECT_PROGRESSIVE_DISABLED_FOR_MOUNT reason=" + reason);
            Console.WriteLine("DIRECT_PROGRESSIVE_FALLBACK reason=" + reason);
            return false;
        }
        finally
        {
            upload.Dispose();
            // On a verified successful MOVE the private stage was consumed.
            // DELETE on that now-missing path used to add another synchronous
            // NAS round-trip to Explorer's 99% tail.
            if (!moveVerified)
            {
                handle.AbandonedStages.Add(upload.StagePath);
                CleanupAbandonedStages(handle);
            }
        }
    }

    internal void PlanCopy(Handle handle, long length)
    {
        lock (handle.Gate)
        {
            if (handle.Created && handle.Local.Length == 0 &&
                handle.Progressive is null && !handle.ProgressiveUnavailable &&
                Volatile.Read(ref _progressiveUnsupported) == 0 &&
                length >= 2 * 1024 * 1024 &&
                !WriteIntent.IsDocument(handle.RemotePath))
                handle.PlannedCopySize = length;
        }
    }

    private static string ProgressiveFailureCode(Exception error)
    {
        // Do not expose filenames, server addresses or credentials in logs.
        string message = error.Message;
        if (message.Contains("stage PUT rejected HTTP", StringComparison.Ordinal))
        {
            var m = System.Text.RegularExpressions.Regex.Match(message,
                @"HTTP\s+([345][0-9]{2})");
            return "stage_put_http_" + (m.Success ? m.Groups[1].Value : "unknown");
        }
        if (message.Contains("stage size/ETag", StringComparison.Ordinal))
            return "stage_metadata_unverified";
        if (message.Contains("stage was not completely written", StringComparison.Ordinal))
            return "stage_incomplete";
        return error.GetType().Name;
    }

    internal int Read(Handle handle, long offset, Span<byte> output)
    {
        lock (handle.Gate)
        {
            handle.Local.Seek(offset, SeekOrigin.Begin);
            return handle.Local.Read(output);
        }
    }

    // Owner files are advisory Office metadata, not workbook payloads.
    // The server must still accept exclusive creation. When a NAS does not
    // expose a strong ETag, permit updates only to the exact owner bytes
    // previously published by this live handle (or verified on reopening).
    // If-Match:* prevents recreating a deleted owner. It is not a revision
    // validator, so a non-cooperating client replacing a marker between GET
    // and PUT remains an inherent limitation of this advisory lock protocol.
    private void CommitOfficeOwner(Handle handle)
    {
        if (handle.UploadMayHaveCommitted)
            throw new IOException("Previous Excel owner write outcome uncertain");
        if (handle.Local.Length > DirectWebDavReadOnlyBackend.MaxOfficeOwnerBytes)
            throw new IOException("Excel owner payload exceeds expected size");
        string path = handle.RemotePath;
        byte[] payload = new byte[(int)handle.Local.Length];
        handle.Local.Seek(0, SeekOrigin.Begin);
        handle.Local.ReadExactly(payload);
        bool create = handle.Created;
        string? match = null;
        if (!create)
        {
            var previous = _read.ReadOfficeOwnerFresh(path);
            if (previous?.Bytes is not { } old ||
                handle.PublishedOfficeOwner is not { } ours ||
                !CryptographicOperations.FixedTimeEquals(old, ours))
                throw new CooperativeLocks.LockConflictException(
                    "Excel owner no longer belongs to this handle");
            string? actual = previous.Entry.ETag;
            if (StrongEtag(handle.ExpectedEtag) && StrongEtag(actual) &&
                !string.Equals(handle.ExpectedEtag, actual, StringComparison.Ordinal))
                throw new CooperativeLocks.LockConflictException("Excel owner revision changed");
            match = StrongEtag(actual) ? actual :
                StrongEtag(handle.ExpectedEtag) ? handle.ExpectedEtag : "*";
        }

        SaveJournal(handle, "uploading");
        using var request = new HttpRequestMessage(HttpMethod.Put, Remote(path));
        request.Headers.TryAddWithoutValidation(create ? "If-None-Match" : "If-Match",
            create ? "*" : match);
        request.Content = new ByteArrayContent(payload);
        handle.UploadMayHaveCommitted = true;
        using var response = _http.Send(request, HttpCompletionOption.ResponseHeadersRead);
        if (response.StatusCode is HttpStatusCode.PreconditionFailed or
            HttpStatusCode.Conflict or HttpStatusCode.Locked)
        {
            handle.UploadMayHaveCommitted = false;
            Console.WriteLine("DIRECT_EXCEL_OWNER_PUT_REJECTED status=" +
                (int)response.StatusCode + " create=" + create +
                " wildcard=" + (match == "*"));
            throw new CooperativeLocks.LockConflictException(
                "Excel owner PUT rejected HTTP " + (int)response.StatusCode);
        }
        if (response.StatusCode is not (HttpStatusCode.OK or
            HttpStatusCode.Created or HttpStatusCode.NoContent))
            throw new IOException("Excel owner PUT HTTP " + (int)response.StatusCode);

        // A successful PUT must correspond to real server bytes, not a
        // synthesized overlay entry. Do not require GET/PROPFIND to contain
        // an ETag, or Excel will delete its owner on otherwise usable NASes.
        DirectWebDavReadOnlyBackend.OfficeOwnerSnapshot? committed = null;
        for (int attempt = 0; attempt < 3; attempt++)
        {
            committed = _read.ReadOfficeOwnerFresh(path);
            if (committed?.Bytes is { } saved &&
                CryptographicOperations.FixedTimeEquals(saved, payload))
                break;
            if (attempt < 2) Thread.Sleep(40 * (attempt + 1));
        }
        if (committed?.Bytes is not { } confirmed ||
            !CryptographicOperations.FixedTimeEquals(confirmed, payload))
        {
            Console.WriteLine("DIRECT_EXCEL_OWNER_SERVER_VISIBILITY_FAILED present=" +
                (committed is not null) + " bytesMatch=false");
            throw new IOException("Excel owner PUT was not visible on server");
        }
        var revision = committed.Entry;
        if (!StrongEtag(revision.ETag) && StrongEtag(response.Headers.ETag?.ToString()))
            revision = revision with { ETag = response.Headers.ETag!.ToString() };
        handle.ExpectedEtag = revision.ETag;
        handle.CommittedEntry = revision;
        handle.PublishedOfficeOwner = confirmed;
        handle.Created = false;
        handle.Dirty = false;
        handle.UploadMayHaveCommitted = false;
        _read.RecordCommittedNewFile(revision);
        SaveJournal(handle, "committed");
        Console.WriteLine("DIRECT_EXCEL_OWNER_SERVER_CONFIRMED create=" + create +
            " bytes=" + payload.Length + " condition=" +
            (create ? "exclusive-create" : match == "*" ? "owned-existing" : "strong-etag") +
            " strongEtag=" + StrongEtag(revision.ETag));
    }

    internal void Commit(Handle handle)
    {
        RequireVerifiedConditionalWrites();
        lock (handle.Gate)
        {
            if (!handle.Dirty) return;
            if (handle.Closed) throw new ObjectDisposedException(nameof(Handle));
            RejectEmptyCommit(handle);
            string path = handle.RemotePath;
            lock (_locks.GetOrAdd(path, _ => new object()))
            {
                if (ExcelOwnerInterop.IsOwnerFile(path) &&
                    (handle.Created || handle.PublishedOfficeOwner is not null))
                {
                    CommitOfficeOwner(handle);
                    return;
                }
                if (!handle.Created && !StrongEtag(handle.ExpectedEtag) &&
                    handle.UnversionedBaseHash is not null)
                {
                    CommitUnversionedOffice(handle);
                    return;
                }
                var commitTimer = System.Diagnostics.Stopwatch.StartNew();
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
                long journalMs = commitTimer.ElapsedMilliseconds;
                using var request = new HttpRequestMessage(HttpMethod.Put, Remote(path));
                bool newFile = handle.Created;
                if (newFile)
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
                long putMs = commitTimer.ElapsedMilliseconds - journalMs;
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
                string? acceptedEtag = result.Headers.ETag?.ToString();
                if (newFile)
                {
                    // Normal file copies require exactly one successful
                    // conditional PUT. The server's HTTP success is the
                    // acknowledgement; no HEAD, PROPFIND, or re-GET after
                    // every newly copied file, regardless of extension.
                    // ETags are saved when supplied, never fabricated.
                    committed = new DirectWebDavReadOnlyBackend.Entry(
                        path, path.Split('/').Last(), false, handle.Local.Length,
                        DateTime.UtcNow, acceptedEtag);
                    if (OfficePayload(path))
                    {
                        using var verify = new HttpRequestMessage(HttpMethod.Get, Remote(path));
                        verify.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
                        using var checkedBody = _http.Send(verify, HttpCompletionOption.ResponseHeadersRead);
                        if (!checkedBody.IsSuccessStatusCode)
                            throw new IOException("Office stage verification GET HTTP " + (int)checkedBody.StatusCode);
                        handle.Local.Position = 0;
                        if (!CryptographicOperations.FixedTimeEquals(
                            SHA256.HashData(handle.Local), SHA256.HashData(checkedBody.Content.ReadAsStream())))
                        {
                            SaveJournal(handle, "office-verification-failed");
                            Console.WriteLine("DIRECT_WEBDAV_OFFICE_STAGE_MISMATCH file=" + OfficeTrace(path));
                            throw new IOException("Office stage readback mismatch; recovery retained");
                        }
                        committed = committed with { ETag = checkedBody.Headers.ETag?.ToString() };
                        Console.WriteLine("DIRECT_WEBDAV_OFFICE_STAGE_VERIFIED file=" + OfficeTrace(path) +
                            " bytes=" + handle.Local.Length + " placeholder=" + (handle.Local.Length == 0));
                    }
                    if (StrongEtag(committed.ETag))
                        _read.RecordCommittedNewFile(committed);
                    else
                        _read.Invalidate(path);
                    Console.WriteLine("DIRECT_WEBDAV_PUT_ACCEPTED new=true" +
                        " strongEtag=" + StrongEtag(acceptedEtag));
                }
                // Existing-file overwrites still use the server revision.
                for (int attempt = 0; committed is null && attempt < 5; attempt++)
                {
                    _read.Invalidate(path);
                    committed = _read.Stat(path);
                    if (committed is not null &&
                        committed.Size == handle.Local.Length &&
                        StrongEtag(committed.ETag)) break;
                    if (attempt < 4) Thread.Sleep(150 * (attempt + 1));
                }
                if (committed is null || committed.Size != handle.Local.Length ||
                    (!newFile && !StrongEtag(committed.ETag)))
                {
                    SaveJournal(handle, "verification-needed");
                    Console.Error.WriteLine("DIRECT_WEBDAV_WRITE_VERIFY_METADATA_FAILED" +
                        " stage=POST_PUT_METADATA sizeMatches=" +
                        (committed?.Size == handle.Local.Length) +
                        " etagStrong=" + StrongEtag(committed?.ETag));
                    throw new IOException("WebDAV POST_PUT_METADATA verification failed; recovery retained");
                }
                long metadataMs = commitTimer.ElapsedMilliseconds -
                    journalMs - putMs;
                // Never re-download a newly copied file just to verify it.
                // Existing-file edit consistency is a separate workflow.
                if (!newFile && (handle.Local.Length <= 16 * 1024 * 1024 ||
                    ExcelOwnerInterop.IsWorkbook(path)))
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
                handle.CommittedEntry = committed;
                handle.Created = false;
                handle.Dirty = false;
                handle.UploadMayHaveCommitted = false;
                SaveJournal(handle, "committed");
                RecordConfirmedPayload(handle);
                if (IsOfficeSaveTemporary(path) || ExcelOwnerInterop.IsWorkbook(path))
                {
                    handle.Local.Seek(0, SeekOrigin.Begin);
                    byte[] hash = SHA256.HashData(handle.Local);
                    _publishedOfficeTemps[path] = hash;
                    handle.UnversionedBaseHash = StrongEtag(committed.ETag) ? null : hash;
                    handle.Local.Seek(0, SeekOrigin.Begin);
                }
                Console.WriteLine("DIRECT_WEBDAV_COPY_TIMING_MS new=" + newFile +
                    " bytes=" + handle.Local.Length +
                    " journal=" + journalMs +
                    " put=" + putMs +
                    " metadata=" + metadataMs +
                    " finalize=" + (commitTimer.ElapsedMilliseconds -
                        journalMs - putMs - metadataMs) +
                    " total=" + commitTimer.ElapsedMilliseconds);
            }
        }
    }

    // Synology can expose a valid Excel workbook with no usable ETag.
    // Require verified ownership and compare exact previous document bytes.
    // If-Match:* guards existence, not a content version. Never use this
    // fallback for unrelated files or a workbook with no verified owner.
    private void CommitUnversionedOffice(Handle handle)
    {
        string path = handle.RemotePath;
        bool workbook = ExcelOwnerInterop.IsWorkbook(path);
        if (workbook ? OwnsExcelWorkbook?.Invoke(path) != true :
            !IsOfficeSaveTemporary(path) ||
            !_publishedOfficeTemps.ContainsKey(path))
            throw new CooperativeLocks.LockConflictException(
                "Unversioned Office save no longer has its verified owner");
        using var before = new HttpRequestMessage(HttpMethod.Get, Remote(path));
        before.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        using var previous = _http.Send(before, HttpCompletionOption.ResponseHeadersRead);
        if (!previous.IsSuccessStatusCode)
            throw new IOException("Office revision GET HTTP " + (int)previous.StatusCode);
        byte[] remoteHash = SHA256.HashData(previous.Content.ReadAsStream());
        handle.Local.Seek(0, SeekOrigin.Begin);
        byte[] desired = SHA256.HashData(handle.Local);
        handle.Local.Seek(0, SeekOrigin.Begin);
        if (!CryptographicOperations.FixedTimeEquals(remoteHash, handle.UnversionedBaseHash!))
        {
            if (!handle.UploadMayHaveCommitted ||
                !CryptographicOperations.FixedTimeEquals(remoteHash, desired))
            {
                SaveJournal(handle, "conflict");
                throw new CooperativeLocks.LockConflictException(
                    "Office document bytes changed; recovery retained");
            }
        }
        else if (!CryptographicOperations.FixedTimeEquals(remoteHash, desired))
        {
            if (workbook && OwnsExcelWorkbook?.Invoke(path) != true)
                throw new CooperativeLocks.LockConflictException("Excel owner changed before save");
            SaveJournal(handle, "uploading");
            using var put = new HttpRequestMessage(HttpMethod.Put, Remote(path));
            string? actual = previous.Headers.ETag?.ToString();
            put.Headers.TryAddWithoutValidation("If-Match", StrongEtag(actual) ? actual : "*");
            put.Content = new StreamContent(new NonClosingStream(handle.Local));
            handle.UploadMayHaveCommitted = true;
            using var saved = _http.Send(put, HttpCompletionOption.ResponseHeadersRead);
            if (!saved.IsSuccessStatusCode)
                throw new IOException("Owned Office PUT HTTP " + (int)saved.StatusCode +
                    "; local recovery copy retained");
        }
        using var verify = new HttpRequestMessage(HttpMethod.Get, Remote(path));
        verify.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
        using var result = _http.Send(verify, HttpCompletionOption.ResponseHeadersRead);
        if (!result.IsSuccessStatusCode ||
            !CryptographicOperations.FixedTimeEquals(
                SHA256.HashData(result.Content.ReadAsStream()), desired))
        {
            SaveJournal(handle, "verification-failed");
            throw new IOException("Owned Office save readback mismatch; recovery retained");
        }
        var entry = new DirectWebDavReadOnlyBackend.Entry(path, path.Split('/').Last(),
            false, handle.Local.Length, result.Content.Headers.LastModified?.UtcDateTime ??
                DateTime.UtcNow, result.Headers.ETag?.ToString());
        handle.ExpectedEtag = entry.ETag;
        handle.UnversionedBaseHash = desired;
        handle.CommittedEntry = entry;
        handle.Dirty = false;
        handle.UploadMayHaveCommitted = false;
        _publishedOfficeTemps[path] = desired;
        _read.RecordCommittedNewFile(entry);
        SaveJournal(handle, "committed");
        RecordConfirmedPayload(handle);
        Console.WriteLine("DIRECT_WEBDAV_OWNED_OFFICE_SAVE_WITHOUT_ETAG_VERIFIED");
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
        handle.CommittedEntry = current;
        handle.Created = false;
        handle.Dirty = false;
        SaveJournal(handle, "recovered-verified");
        RecordConfirmedPayload(handle);
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
            if (ExcelOwnerInterop.IsOwnerFile(journal.Path))
            {
                // A ~$ owner is Office-owned metadata, not workbook data.
                // Never restore it after a crash and silently re-lock a NAS
                // document. Only clear this client's local orphan journal;
                // existing remote owner files remain for manual cleanup.
                try
                {
                    File.Delete(ManifestPath(journal.Id));
                    File.Delete(DataPath(journal.Id));
                    Console.WriteLine("DIRECT_EXCEL_OWNER_LOCAL_RECOVERY_DISCARDED");
                }
                catch (IOException) { retained++; }
                continue;
            }
            // A crashed zero-byte Office reservation is not a completed save.
            if (journal.Size == 0 && OfficePayload(journal.Path) &&
                journal.Status is not ("opened" or "committed" or "recovered-verified"))
            {
                retained++;
                Console.WriteLine("DIRECT_WEBDAV_OFFICE_EMPTY_RECOVERY_RETAINED file=" +
                    OfficeTrace(journal.Path) + " journal=" + journal.Id);
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
            // A crashed queued upload must never be silently replayed:
            // another WebDAV client may have deleted the destination since
            // the Explorer copy window closed. Only an exact existing remote
            // match can be retired automatically.
            if (journal.Created && journal.Status is ("queued" or "uploading"))
            {
                retained++;
                Console.Error.WriteLine(
                    "DIRECT_RECOVERY_BACKGROUND_RETAINED_NO_AUTO_REPLAY");
                continue;
            }
            string? leaseKey = null;
            try
            {
                if (ExcelOwnerInterop.IsWorkbook(journal.Path))
                {
                    // Office owner files are managed by Excel, not by a
                    // recovery worker. Never introduce a new sidecar lease
                    // while recovering a workbook. If an owner is still
                    // present (including a stale owner after a crash), keep
                    // the journal for manual recovery instead of overwriting.
                    ExcelOwnerInterop.RequireAvailable(_read, journal.Path);
                }
                else if (WriteIntent.IsDocument(journal.Path))
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

    internal string? RenameNewDestination(string from, string to, string? originalEtag)
    {
        RequireVerifiedConditionalWrites();
        // A zero-byte reservation is not a complete Excel workbook.
        byte[]? workbookHash = null;
        if (ExcelOwnerInterop.IsWorkbook(to))
        {
            var fresh = _read.StatFresh(from);
            if (fresh is null || fresh.IsDirectory)
                throw new FileNotFoundException("Workbook rename source is missing");
            if (fresh.Size <= 0)
                RejectEmptyWorkbookPromotion(from, to);
            if (StrongEtag(originalEtag) && fresh.ETag != originalEtag)
                throw new IOException("Workbook source changed before rename");
            using var get = new HttpRequestMessage(HttpMethod.Get, Remote(from));
            get.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
            if (StrongEtag(originalEtag))
                get.Headers.TryAddWithoutValidation("If-Match", originalEtag);
            using var body = _http.Send(get, HttpCompletionOption.ResponseHeadersRead);
            if (!body.IsSuccessStatusCode)
                throw new IOException("Workbook source verification GET HTTP " + (int)body.StatusCode);
            workbookHash = SHA256.HashData(body.Content.ReadAsStream());
            if (CryptographicOperations.FixedTimeEquals(workbookHash, SHA256.HashData(Array.Empty<byte>())))
                RejectEmptyWorkbookPromotion(from, to);
        }
        // Never use WebDAV MOVE Overwrite:T. Excel normally performs two
        // new-destination renames; replacing an existing original requires
        // a separately verified lock+conditional-commit transaction.
        // A newly written Office temporary file may have no ETag even in
        // fresh PROPFIND. MOVE Overwrite:F is an atomic create-if-absent;
        // never relax the revision guard for arbitrary sources or replacing
        // an existing destination. The random temp must still exist.
        bool newOfficeDocument = !StrongEtag(originalEtag) &&
            _publishedOfficeTemps.ContainsKey(from) &&
            ((IsOfficeSaveTemporary(from) && WriteIntent.IsDocument(to)) ||
             (ExcelOwnerInterop.IsWorkbook(from) && IsOfficeSaveTemporary(to) &&
                OwnsExcelWorkbook?.Invoke(from) == true));
        if (!StrongEtag(originalEtag) && !newOfficeDocument)
            throw new IOException("Unsafe rename without strong source ETag");
        if (newOfficeDocument)
        {
            RequireVerifiedStageMove(to);
            if (!_publishedOfficeTemps.TryGetValue(from, out var expected))
                throw new IOException("Office temporary source is no longer owned");
            if (workbookHash is not null)
            {
                if (!CryptographicOperations.FixedTimeEquals(workbookHash, expected))
                    throw new IOException("Office temporary source changed before rename");
            }
            else
            {
                using var source = new HttpRequestMessage(HttpMethod.Get, Remote(from));
                using var content = _http.Send(source, HttpCompletionOption.ResponseHeadersRead);
                if (!content.IsSuccessStatusCode || !CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(content.Content.ReadAsStream()), expected))
                    throw new IOException("Office temporary source changed before rename");
            }
        }
        if (ExcelOwnerInterop.IsWorkbook(from) && IsOfficeSaveTemporary(to))
        {
            var original = _read.StatFresh(from) ??
                throw new FileNotFoundException("Workbook disappeared before backup rename");
            PreserveOfficeRenameBackup(from, original);
        }
        using var request = new HttpRequestMessage(new HttpMethod("MOVE"), Remote(from));
        request.Headers.TryAddWithoutValidation("Destination", Remote(to).AbsoluteUri);
        request.Headers.TryAddWithoutValidation("Overwrite", "F");
        // Some WebDAV servers reject If-Match:* on MOVE even when the source
        // exists. For our byte-verified, unversioned temporary file, the
        // verified Overwrite:F guard is the destination precondition.
        if (!newOfficeDocument)
            request.Headers.TryAddWithoutValidation("If-Match", originalEtag);
        using var result = _http.Send(request);
        if (result.StatusCode is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
            throw new IOException("Safe WebDAV MOVE failed HTTP " + (int)result.StatusCode);
        _read.Invalidate(from);
        _read.Invalidate(to);
        if (newOfficeDocument || workbookHash is not null)
        {
            using var get = new HttpRequestMessage(HttpMethod.Get, Remote(to));
            get.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
            using var verify = _http.Send(get, HttpCompletionOption.ResponseHeadersRead);
            if (!verify.IsSuccessStatusCode)
                throw new IOException("Office MOVE verification GET HTTP " + (int)verify.StatusCode);
            byte[]? expected = workbookHash;
            if (expected is null) _publishedOfficeTemps.TryGetValue(from, out expected);
            if (expected is null ||
                !CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(verify.Content.ReadAsStream()), expected))
                throw new IOException("Office MOVE destination checksum mismatch");
            _publishedOfficeTemps[to] = expected;
            if (newOfficeDocument)
                Console.WriteLine("DIRECT_WEBDAV_NEW_DOCUMENT_MOVE_WITHOUT_ETAG_ACCEPTED");
            if (workbookHash is not null)
            {
                Console.WriteLine("DIRECT_WEBDAV_OFFICE_FINAL_VERIFIED file=" +
                    OfficeTrace(to) + " source=" + OfficeTrace(from));
                RetireOfficeRenameBackup(to);
            }
        }
        else if (_publishedOfficeTemps.TryGetValue(from, out var movedHash) &&
            (IsOfficeSaveTemporary(to) || ExcelOwnerInterop.IsWorkbook(to)))
            _publishedOfficeTemps[to] = movedHash;
        _publishedOfficeTemps.TryRemove(from, out _);
        return result.Headers.ETag?.ToString();
    }

    // Office often renames a temporary file ON TOP OF an existing workbook.
    // WebDAV MOVE with Overwrite:F fails there; Overwrite:T is unsafe because
    // it can ignore a target revision changed by another NAS client.
    // A conditional PUT to the exact destination ETag, followed by byte
    // verification and conditional source deletion, preserves concurrency.
    internal void UpdateRenamedHandle(Handle handle, string to,
        DirectWebDavReadOnlyBackend.Entry? entry)
    {
        handle.RemotePath = to;
        handle.ExpectedEtag = entry?.ETag;
        handle.CommittedEntry = entry;
        handle.ConfirmedSize = entry?.Size ?? handle.Local.Length;
        handle.UnversionedBaseHash =
            !StrongEtag(entry?.ETag) && _publishedOfficeTemps.TryGetValue(to, out var hash)
                ? hash : null;
    }

    internal void RenameWithVerifiedReplacement(string from, string to,
        string? sourceEtag, bool replaceIfExists)
    {
        RequireVerifiedConditionalWrites();
        var destination = _read.StatFresh(to);
        if (destination is null)
        {
            RenameNewDestination(from, to, sourceEtag);
            return;
        }
        if (!replaceIfExists)
            throw new IOException("Destination already exists");
        if (!StrongEtag(sourceEtag))
            throw new IOException("Unsafe replacement without strong source ETag");
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
            if (ExcelOwnerInterop.IsWorkbook(to) && new FileInfo(stage).Length == 0)
                RejectEmptyWorkbookPromotion(from, to);
            if (ExcelOwnerInterop.IsWorkbook(to) && destination.Size > 0)
                PreserveOfficeRenameBackup(to, destination);

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
            if (ExcelOwnerInterop.IsWorkbook(to))
                RetireOfficeRenameBackup(to);
            Console.WriteLine("DIRECT_WEBDAV_EXCEL_REPLACEMENT_VERIFIED");
        }
        finally { try { File.Delete(stage); } catch (IOException) { } }
    }

    private void RejectEmptyWorkbookPromotion(string from, string to)
    {
        Console.WriteLine("DIRECT_WEBDAV_OFFICE_EMPTY_BLOCKED operation=rename file=" +
            OfficeTrace(to) + " source=" + OfficeTrace(from));
        // Excel may already have moved the previous workbook to its backup.
        // Recover only a missing name, never overwrite another client's file.
        // Keep the durable preimage even when this conditional restore works.
        if (_officeRenameBackups.TryGetValue(to, out var backup))
        {
            try
            {
                if (_read.StatFresh(to) is null)
                {
                    using var manifest = JsonDocument.Parse(File.ReadAllText(backup + ".json"));
                    var meta = manifest.RootElement;
                    using var original = File.OpenRead(backup);
                    string expected = meta.GetProperty("Sha256").GetString()!;
                    if (meta.GetProperty("Path").GetString()!.Equals(to, StringComparison.OrdinalIgnoreCase) &&
                        original.Length > 0 && original.Length == meta.GetProperty("Size").GetInt64() &&
                        Convert.ToHexString(SHA256.HashData(original)) == expected)
                    {
                        original.Position = 0;
                        using var put = new HttpRequestMessage(HttpMethod.Put, Remote(to));
                        put.Headers.TryAddWithoutValidation("If-None-Match", "*");
                        put.Content = new StreamContent(new NonClosingStream(original));
                        using var restored = _http.Send(put, HttpCompletionOption.ResponseHeadersRead);
                        if (restored.IsSuccessStatusCode)
                        {
                            _read.Invalidate(to);
                            using var get = new HttpRequestMessage(HttpMethod.Get, Remote(to));
                            get.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
                            using var verified = _http.Send(get, HttpCompletionOption.ResponseHeadersRead);
                            if (verified.IsSuccessStatusCode &&
                                Convert.ToHexString(SHA256.HashData(verified.Content.ReadAsStream())) == expected)
                                Console.WriteLine("DIRECT_WEBDAV_OFFICE_ORIGINAL_RESTORED file=" + OfficeTrace(to));
                        }
                    }
                }
            }
            catch (Exception ex) when (ex is IOException or HttpRequestException or UnauthorizedAccessException or JsonException)
            {
                Console.WriteLine("DIRECT_WEBDAV_OFFICE_RESTORE_PENDING file=" + OfficeTrace(to));
            }
        }
        throw new IOException("Refusing to promote zero-byte source to an Excel workbook");
    }

    internal void Delete(string path, string? etag, bool directory = false,
        bool explicitUserDelete = false)
    {
        var deleteTimer = System.Diagnostics.Stopwatch.StartNew();
        path = DirectWebDavReadOnlyBackend.Normalize(path);
        if (path == "/")
            throw new IOException("Refusing to delete the WebDAV root");
        // Explorer DELETE is explicit removal of the named object. Synology
        // can change ETags immediately after a copy and reject DELETE
        // If-Match with HTTP 412. Internal Office replacement source cleanup
        // still retains strict conditional deletion.
        if (directory)
        {
            var children = _read.RefreshList(path);
            if (children.Length != 0)
                throw new IOException("Directory is not empty on NAS");
        }
        else if (!explicitUserDelete) RequireVerifiedConditionalWrites();
        if (!directory && !explicitUserDelete && !StrongEtag(etag))
            throw new IOException("Refusing DELETE without strong source ETag");
        if (!directory && explicitUserDelete && ExcelOwnerInterop.IsWorkbook(path) &&
            OwnsExcelWorkbook?.Invoke(path) == true && _read.StatFresh(path) is { } owned)
            PreserveOfficeRenameBackup(path, owned);
        using var request = new HttpRequestMessage(HttpMethod.Delete, Remote(path));
        if (!directory && !explicitUserDelete && StrongEtag(etag))
            request.Headers.TryAddWithoutValidation("If-Match", etag);
        using var result = _http.Send(request);
        if (!result.IsSuccessStatusCode &&
            !(explicitUserDelete && result.StatusCode == HttpStatusCode.NotFound))
            throw new IOException("WebDAV DELETE rejected HTTP " + (int)result.StatusCode);
        _read.Invalidate(path);
        _publishedOfficeTemps.TryRemove(path, out _);
        if (directory)
        {
            // No stale directory listing can remain after a successful
            // server DELETE, even if the Explorer view is immediately opened.
            string parent = path[..path.LastIndexOf('/')];
            _read.Invalidate(string.IsNullOrEmpty(parent) ? "/" : parent);
        }
        Console.WriteLine("DIRECT_WEBDAV_DELETE_TIMING_MS directory=" +
            directory + " explicit=" + explicitUserDelete +
            " elapsed=" + deleteTimer.ElapsedMilliseconds);
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
            // Empty Excel placeholders are valid while Save As is in
            // progress; publishing one as the finished document is not.
            string guarded = "/" + folder + "/zero-guard.xlsx";
            string emptyTemp = "/" + folder + "/A1B2C3D4";
            string oldTemp = "/" + folder + "/A1B2C3D5.tmp";
            string absent = "/" + folder + "/new-zero.xlsx";
            byte[] originalBytes = Encoding.UTF8.GetBytes("nonempty workbook revision");
            using (var h = write.Begin(guarded, create: true))
            {
                write.Write(h, 0, originalBytes);
                write.Close(h, commit: true);
            }
            using var officeCheck = new HttpClient();
            using (var h = write.Begin(guarded, create: false))
            {
                write.SetLength(h, 0);
                bool blocked = false;
                try { write.Commit(h); }
                catch (IOException) { blocked = true; }
                if (!blocked || !officeCheck.GetByteArrayAsync(write.Remote(guarded))
                        .GetAwaiter().GetResult().SequenceEqual(originalBytes) ||
                    h.OfficeSafetyBackup is null ||
                    !File.ReadAllBytes(h.OfficeSafetyBackup).SequenceEqual(originalBytes))
                    throw new IOException("Zero truncation corrupted the original workbook");
                write.Close(h, commit: false);
            }
            using (var h = write.Begin(emptyTemp, create: true))
                write.Close(h, commit: true);
            var emptyRevision = read.StatFresh(emptyTemp) ??
                throw new IOException("Legitimate empty temporary was not created");
            foreach (string target in new[] { guarded, absent })
            {
                bool blocked = false;
                try { write.RenameWithVerifiedReplacement(emptyTemp, target, emptyRevision.ETag, true); }
                catch (IOException) { blocked = true; }
                if (!blocked)
                    throw new IOException("Empty temporary became a workbook");
            }
            if (read.StatFresh(absent) is not null ||
                !officeCheck.GetByteArrayAsync(write.Remote(guarded)).GetAwaiter().GetResult().SequenceEqual(originalBytes))
                throw new IOException("Zero rename did not preserve the original");
            var prior = read.StatFresh(guarded)!;
            write.RenameNewDestination(guarded, oldTemp, prior.ETag);
            if (read.StatFresh(guarded) is not null)
                throw new IOException("Office backup rename was not exercised");
            bool restored = false;
            try { write.RenameNewDestination(emptyTemp, guarded, emptyRevision.ETag); }
            catch (IOException) { restored = true; }
            if (!restored || !officeCheck.GetByteArrayAsync(write.Remote(guarded))
                .GetAwaiter().GetResult().SequenceEqual(originalBytes))
                throw new IOException("Missing original was not safely restored after zero promotion");
            Console.WriteLine("DIRECT_WEBDAV_ZERO_WORKBOOK_COMMIT_AND_RENAME_GUARD_PASS");

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
            string deferred = "/" + folder + "/queued-external-delete.txt";
            string deferredId;
            using (var queued = write.Begin(deferred, create: true,
                fastCopy: true))
            {
                deferredId = queued.Id;
                write.Write(queued, 0,
                    Encoding.UTF8.GetBytes("new file not yet on NAS"));
                write.QueueNewFile(queued);
                // Mimic a crash before the background uploader starts.
                // A different client may have deleted the folder meanwhile.
            }
            recovery = restart.RecoverPending();
            if (read.StatFresh(deferred) is not null ||
                !write.PendingRecovery().Any(x => x.Id == deferredId) ||
                recovery.retained < 1)
                throw new IOException(
                    "Queued background copy was replayed after an external deletion");
            Console.WriteLine(
                "DIRECT_WEBDAV_QUEUED_RECOVERY_NO_RESURRECTION_PASS");
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
