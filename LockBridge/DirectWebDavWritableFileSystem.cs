using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using Fsp.Interop;
using FileInfo = Fsp.Interop.FileInfo;

namespace NASMount.LockBridge;

/// <summary>
/// Development-only writable one-WinFsp layer, using verified WebDAV
/// conditional requests and a durable local recovery journal. A real NAS
/// profile is NOT routed here until native/exclusive Office locks, recovery
/// and full external-client safety are proved.
/// </summary>
internal sealed class DirectWebDavWritableFileSystem : DirectWebDavReadOnlyFileSystem
{
    private const int Success = 0;
    private const int NotFound = unchecked((int)0xC0000034);
    private const int AccessDenied = unchecked((int)0xC0000022);
    private const int Collision = unchecked((int)0xC0000035);
    private const int IoError = unchecked((int)0xC0000185);
    private const int EndOfFile = unchecked((int)0xC0000011);
    private readonly DirectWebDavWriteStore _writes;
    private readonly LeaseCoordinator? _leases;
    private readonly object _excelOwnerGate = new();
    private readonly Dictionary<string, int> _localExcelEditors =
        new(StringComparer.OrdinalIgnoreCase);
    private sealed record OwnedExcelMarker(string? Etag, byte[] Sha256, long Size);
    private readonly ConcurrentDictionary<string, OwnedExcelMarker> _ownerFilesPublishedHere =
        new(StringComparer.OrdinalIgnoreCase);
    private const string ExcelOwnerLeasePrefix = "excel-owner-only:";
    private int _activeWriteHandles;
    internal int ActiveWriteHandles => Volatile.Read(ref _activeWriteHandles);
    // Explorer may retain read-only metadata handles for icon caching.
    // Only writers, unfinished uploads and held leases block safe unmount.
    internal bool CanShutdown => ActiveWriteHandles == 0 &&
        OutstandingUploads == 0 && (_leases?.CanShutdown ?? true) &&
        !_writes.HasUncommittedRecovery;
    private readonly ConcurrentDictionary<string, DirectWebDavReadOnlyBackend.Entry> _overlay =
        new(StringComparer.OrdinalIgnoreCase);
    // A fast Explorer copy is a durable local-cache acknowledgement, not
    // confirmation of upload. The bounded worker pool tracks NAS completion.
    private readonly SemaphoreSlim _uploadSlots = new(64, 64);
    private readonly ConcurrentDictionary<string, PendingCopy> _pendingCopies =
        new(StringComparer.OrdinalIgnoreCase);
    private int _outstandingUploads;
    internal int OutstandingUploads => Volatile.Read(ref _outstandingUploads);
    private int _uploadingNow;
    private int _failedUploads;
    private long _confirmedUploads;
    private long _acceptedUploads;
    private long _cancelledUploads;
    internal int UploadingNow => Volatile.Read(ref _uploadingNow);
    internal int UploadsQueued => Math.Max(0,
        OutstandingUploads - UploadingNow);
    internal int FailedUploads => Volatile.Read(ref _failedUploads);
    internal long ConfirmedUploads => Interlocked.Read(ref _confirmedUploads);
    internal long AcceptedUploads => Interlocked.Read(ref _acceptedUploads);
    internal long CancelledUploads => Interlocked.Read(ref _cancelledUploads);
    private sealed class PendingCopy(WriteDescriptor writer)
    {
        internal readonly WriteDescriptor Writer = writer;
        internal readonly TaskCompletionSource<bool> Completion =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal int DeleteRequested;
        internal int Started;
    }
    private sealed class PendingReadDescriptor(
        DirectWebDavReadOnlyBackend.Entry entry, FileStream stream)
    {
        internal readonly DirectWebDavReadOnlyBackend.Entry Entry = entry;
        internal readonly FileStream Stream = stream;
    }
    private readonly ConcurrentDictionary<string, string> _ownVerifiedEtags =
        new(StringComparer.OrdinalIgnoreCase);
    protected override bool PauseExternalChangeNotification =>
        ActiveWriteHandles > 0;

    private void RecordCommittedRevision(WriteDescriptor writer)
    {
        // Progressive commit already fetched and verified the final ETag
        // and length. Do not issue the same NAS PROPFIND again just because
        // WinFsp Flush/Cleanup requests the updated descriptor metadata.
        var committed = writer.Session.CommittedEntry;
        if (committed is null ||
            !committed.Path.Equals(writer.Entry.Path, StringComparison.OrdinalIgnoreCase))
            committed = _backend.Stat(writer.Entry.Path);
        if (committed is null) return;
        writer.Entry = committed;
        if (DirectWebDavWriteStore.StrongEtag(committed.ETag))
            _ownVerifiedEtags[committed.Path] = committed.ETag!;
    }

    protected override bool IsOwnVerifiedRevision(
        DirectWebDavReadOnlyBackend.Entry before,
        DirectWebDavReadOnlyBackend.Entry after)
    {
        if (!_ownVerifiedEtags.TryGetValue(after.Path, out var own) ||
            !string.Equals(own, after.ETag, StringComparison.Ordinal))
            return false;
        _ownVerifiedEtags.TryRemove(after.Path, out _);
        return true;
    }

    private sealed class WriteDescriptor
    {
        internal readonly DirectWebDavWriteStore.Handle Session;
        internal DirectWebDavReadOnlyBackend.Entry Entry;
        internal bool Deleted;
        internal bool Closed;
        internal bool Queued;
        internal string? LeaseKey;

        internal WriteDescriptor(DirectWebDavWriteStore.Handle session,
            DirectWebDavReadOnlyBackend.Entry entry)
        {
            Session = session;
            Entry = entry;
        }
    }

    private sealed class AttributeDescriptor(
        DirectWebDavReadOnlyBackend.Entry entry)
    {
        internal DirectWebDavReadOnlyBackend.Entry Entry = entry;
    }

    private bool QueueCopy(WriteDescriptor writer)
    {
        if (!writer.Session.WriteBehind || writer.Queued) return false;
        if (!_writes.QueueNewFile(writer.Session)) return false;
        var job = new PendingCopy(writer);
        if (!_pendingCopies.TryAdd(writer.Entry.Path, job))
            throw new IOException("A copy of this name is already queued");
        writer.Queued = true;
        int pending = Interlocked.Increment(ref _outstandingUploads);
        Interlocked.Increment(ref _acceptedUploads);
        Console.WriteLine("DIRECT_WEBDAV_WRITE_BEHIND_LOCAL_QUEUED bytes=" +
            writer.Session.Local.Length + " pending=" + pending +
            " remoteCommitted=false");
        _ = Task.Run(() => ProcessPendingCopy(job));
        return true;
    }

    private async Task ProcessPendingCopy(PendingCopy job)
    {
        var writer = job.Writer;
        string key = writer.Entry.Path;
        bool slot = false;
        bool successful = false;
        bool canceled = false;
        try
        {
            await _uploadSlots.WaitAsync().ConfigureAwait(false);
            slot = true;
            Interlocked.Increment(ref _uploadingNow);
            Interlocked.Exchange(ref job.Started, 1);
            if (Volatile.Read(ref job.DeleteRequested) != 0)
            {
                _writes.Discard(writer.Session);
                successful = true;
                canceled = true;
            }
            else
            {
                for (int attempt = 1; attempt <= 3; attempt++)
                {
                    try
                    {
                        _writes.Commit(writer.Session);
                        if (Volatile.Read(ref job.DeleteRequested) != 0)
                        {
                            _writes.Delete(key, writer.Session.ExpectedEtag,
                                explicitUserDelete: true);
                            canceled = true;
                        }
                        _writes.Close(writer.Session, commit: false);
                        successful = true;
                        break;
                    }
                    catch (Exception ex) when (ex is IOException or
                        HttpRequestException or InvalidOperationException)
                    {
                        // A WebDAV conflict, HTTP 412/423, or an upload whose
                        // outcome is uncertain must NOT be retried blindly.
                        // Another client may have deleted/replaced the path;
                        // a later retry could recreate the file after their
                        // successful DELETE. Preserve local recovery instead.
                        bool terminal = writer.Session.UploadMayHaveCommitted ||
                            ex.Message.Contains("Concurrent NAS edit",
                                StringComparison.Ordinal) ||
                            ex.Message.Contains("conflict",
                                StringComparison.OrdinalIgnoreCase) ||
                            ex.Message.Contains("HTTP 412",
                                StringComparison.Ordinal) ||
                            ex.Message.Contains("HTTP 423",
                                StringComparison.Ordinal) ||
                            // Another client may have removed the parent
                            // directory. Retrying 404/405/409 against a path
                            // recreated later would resurrect deleted data.
                            ex.Message.Contains("HTTP 404",
                                StringComparison.Ordinal) ||
                            ex.Message.Contains("HTTP 405",
                                StringComparison.Ordinal) ||
                            ex.Message.Contains("HTTP 409",
                                StringComparison.Ordinal);
                        if (terminal || attempt == 3)
                        {
                            Console.Error.WriteLine(
                                "DIRECT_WEBDAV_WRITE_BEHIND_FAILED id=" +
                                writer.Session.Id + " pendingRecovery=true type=" +
                                ex.GetType().Name + " attempts=" + attempt +
                                " terminal=" + terminal);
                            break;
                        }
                        await Task.Delay(attempt * 500).ConfigureAwait(false);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine("DIRECT_WEBDAV_WRITE_BEHIND_FAILED id=" +
                writer.Session.Id + " pendingRecovery=true type=" +
                ex.GetType().Name);
        }
        finally
        {
            if (!successful)
            {
                Interlocked.Increment(ref _failedUploads);
                try { writer.Session.Dispose(); } catch (IOException) { }
            }
            else if (canceled) Interlocked.Increment(ref _cancelledUploads);
            else Interlocked.Increment(ref _confirmedUploads);
            if (successful)
            {
                _pendingCopies.TryRemove(key, out _);
                _overlay.TryRemove(key, out _);
            }
            job.Completion.TrySetResult(successful);
            if (slot)
            {
                Interlocked.Decrement(ref _uploadingNow);
                _uploadSlots.Release();
            }
            int pending = Interlocked.Decrement(ref _outstandingUploads);
            Interlocked.Decrement(ref _activeWriteHandles);
            ReleaseOfficeLease(writer.LeaseKey);
            writer.LeaseKey = null;
            Console.WriteLine("DIRECT_WEBDAV_WRITE_BEHIND_STATUS pending=" +
                pending + " remoteCommitted=" + (successful && !canceled) +
                " canceled=" + canceled +
                " recoveryRetained=" + !successful);
        }
    }

    internal DirectWebDavWritableFileSystem(DirectWebDavReadOnlyBackend backend,
        DirectWebDavWriteStore store, string aclDirectory,
        LeaseCoordinator? leases = null) : base(backend, aclDirectory)
    {
        _writes = store;
        _leases = leases;
        _writes.OwnsExcelWorkbook = HasVerifiedExcelOwner;
        _writes.HasLegacyExcelSession = HasLegacyExcelSession;
        _writes.HasLocalExcelSession = HasLocalExcelSession;
    }

    private bool HasVerifiedExcelOwner(string workbook)
    {
        if (!ExcelOwnerInterop.IsWorkbook(workbook)) return false;
        foreach (string owner in ExcelOwnerInterop.PossibleOwners(workbook))
        {
            if (!_ownerFilesPublishedHere.ContainsKey(owner)) continue;
            var snapshot = _backend.ReadOfficeOwnerFresh(owner);
            if (snapshot is not null && IsOurCurrentExcelOwner(owner, snapshot.Entry.ETag))
                return true;
        }
        return false;
    }

    // Excel 97-2003 does not always publish a ~$ file. A local writer
    // session and a fresh foreign-owner check are required in addition to
    // the store's exact remote preimage check. Do not synthesize owner files.
    private bool HasLegacyExcelSession(string path)
    {
        if (!Path.GetExtension(path).Equals(".xls", StringComparison.OrdinalIgnoreCase))
            return false;
        return HasLocalExcelSession(path);
    }

    private bool HasLocalExcelSession(string path)
    {
        if (!ExcelOwnerInterop.IsWorkbook(path)) return false;
        string workbook = DirectWebDavReadOnlyBackend.Normalize(path);
        lock (_excelOwnerGate)
            if (!_localExcelEditors.ContainsKey(workbook)) return false;
        try
        {
            ExcelOwnerInterop.RequireAvailable(_backend, workbook,
                (owner, etag) => IsOurCurrentExcelOwner(owner, etag));
            return true;
        }
        catch (IOException) { return false; }
    }

    public override int GetVolumeInfo(out VolumeInfo info)
    {
        // The local journal's C:/D: free space is NOT the NAS capacity.
        // The parent has an asynchronously cached RFC 4331 remote quota,
        // falling back to RaiDrive-style virtual unlimited display.
        // Existing journal write checks and I/O exceptions remain intact.
        return base.GetVolumeInfo(out info);
    }

    private string? AcquireOfficeLease(string path)
    {
        if (ExcelOwnerInterop.IsWorkbook(path))
        {
            // Shared marker is Microsoft's actual ~$ owner file. The first
            // handle must check the NAS without stale metadata caches.
            // Subsequent handles from THIS mounted editor share its local
            // session, so Excel can reopen during its atomic save.
            string workbook = DirectWebDavReadOnlyBackend.Normalize(path);
            lock (_excelOwnerGate)
            {
                if (!_localExcelEditors.TryGetValue(workbook, out int count))
                {
                    ExcelOwnerInterop.RequireAvailable(_backend, workbook,
                        (ownerPath, remoteEtag) =>
                            IsOurCurrentExcelOwner(ownerPath, remoteEtag));
                    count = 0;
                }
                _localExcelEditors[workbook] = count + 1;
            }
            return ExcelOwnerLeasePrefix + workbook;
        }
        if (_leases is null || !WriteIntent.IsDocument(path)) return null;
        string key = path.TrimStart('/').Replace('\\', '/');
        _leases.AddHandle(key);
        return key;
    }

    private bool IsOurCurrentExcelOwner(string ownerPath, string? remoteEtag)
    {
        if (!_ownerFilesPublishedHere.TryGetValue(ownerPath, out var owner))
            return false;
        // Two strong revisions identify a remote replacement even if the
        // other Windows editor has the same account/display name.
        if (DirectWebDavWriteStore.StrongEtag(owner.Etag) &&
            DirectWebDavWriteStore.StrongEtag(remoteEtag))
            return string.Equals(owner.Etag, remoteEtag, StringComparison.Ordinal);
        // Synology often omits a strong ETag on the initial PUT response.
        // Only for the tiny owner file published by THIS mount, verify the
        // actual bytes instead. Unrelated Explorer files never incur GETs.
        var snapshot = _backend.ReadOfficeOwnerFresh(ownerPath);
        if (snapshot?.Bytes is not { } bytes || bytes.Length != owner.Size)
            return false;
        return CryptographicOperations.FixedTimeEquals(owner.Sha256,
            SHA256.HashData(bytes));
    }

    private void PublishExcelOwnerRevision(WriteDescriptor writer)
    {
        if (!ExcelOwnerInterop.IsOwnerFile(writer.Entry.Path)) return;
        var session = writer.Session;
        lock (session.Gate)
        {
            if (session.PublishedOfficeOwner is null) return;
            if (session.Dirty || session.Local.Length >
                DirectWebDavReadOnlyBackend.MaxOfficeOwnerBytes)
                throw new IOException("Excel owner was not committed completely");
            session.Local.Seek(0, SeekOrigin.Begin);
            byte[] sha = SHA256.HashData(session.Local);
            _ownerFilesPublishedHere[writer.Entry.Path] = new OwnedExcelMarker(
                session.CommittedEntry?.ETag, sha, session.Local.Length);
        }
        Console.WriteLine("DIRECT_EXCEL_OWNER_REMOTE_VISIBLE on=write" +
            " strongEtag=" + DirectWebDavWriteStore.StrongEtag(
                session.CommittedEntry?.ETag));
    }

    private void ReleaseOfficeLease(string? key)
    {
        if (key is null) return;
        if (key.StartsWith(ExcelOwnerLeasePrefix, StringComparison.Ordinal))
        {
            string path = key[ExcelOwnerLeasePrefix.Length..];
            lock (_excelOwnerGate)
            {
                if (!_localExcelEditors.TryGetValue(path, out int count)) return;
                if (count <= 1) _localExcelEditors.Remove(path);
                else _localExcelEditors[path] = count - 1;
            }
            return;
        }
        _leases?.ReleaseHandle(key);
    }

    private void ReleaseExcelOwnerSessionAtCleanup(object descriptor)
    {
        // WinFsp Close can arrive well after the last Windows handle is
        // closed. Re-check the actual NAS ~$ owner on every new Excel open,
        // not after delayed kernel-object disposal.
        if (descriptor is not WriteDescriptor writer ||
            writer.LeaseKey is not { } lease ||
            !lease.StartsWith(ExcelOwnerLeasePrefix, StringComparison.Ordinal))
            return;
        ReleaseOfficeLease(lease);
        writer.LeaseKey = null;
    }

    protected override FileInfo DescribeEntry(
        DirectWebDavReadOnlyBackend.Entry entry)
    {
        var info = base.DescribeEntry(entry);
        if (!entry.IsDirectory)
        {
            info.FileAttributes &= ~(uint)System.IO.FileAttributes.ReadOnly;
            info.FileAttributes |= (uint)System.IO.FileAttributes.Archive;
        }
        return info;
    }

    private FileInfo Describe(WriteDescriptor descriptor)
    {
        // Excel repeatedly queries the same open handle and treats changes
        // in last-write time as evidence of an external editor. Returning
        // DateTime.UtcNow on EVERY call caused false "another user saved"
        // notifications even when the remote file had not changed.
        var local = descriptor.Entry with {
            Size = descriptor.Session.Local.Length
        };
        return DescribeEntry(local);
    }

    private static int Failure(Exception error, string callback)
    {
        Console.Error.WriteLine("DIRECT_ONE_LAYER_IO_ERROR callback=" + callback +
            " type=" + error.GetType().Name + " detail=" + error.Message);
        return error is FileNotFoundException ? NotFound :
            error is CooperativeLocks.LockConflictException
                ? unchecked((int)0xC0000043) : // STATUS_SHARING_VIOLATION
            error is UnauthorizedAccessException ? AccessDenied :
            error.Message.Contains("already exists", StringComparison.OrdinalIgnoreCase)
                ? Collision : IoError;
    }

    // Cooperative MKCOL lease folders stay on the server to serialize
    // writers across PCs. Hide them from our mounted namespace, including
    // owner.json and every nested path, without touching their NAS contents.
    private static bool IsOwnLockArtifact(string path) =>
        path.Replace('\\', '/')
            .Split('/', StringSplitOptions.RemoveEmptyEntries)
            .Any(segment =>
                segment.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) ||
                segment.StartsWith(".nasmount-stage-", StringComparison.OrdinalIgnoreCase));

    protected override bool IsInternalPath(string path) => IsOwnLockArtifact(path);

    protected override DirectWebDavReadOnlyBackend.Entry[] ListForDirectory(string directory)
    {
        var remote = _backend.List(directory);
        var existing = remote.Where(x => !IsOwnLockArtifact(x.Path))
            .ToDictionary(x => x.Name,
            StringComparer.OrdinalIgnoreCase);
        foreach (var added in _overlay.Values)
        {
            if (IsOwnLockArtifact(added.Path)) continue;
            string parent = DirectWebDavReadOnlyBackend.Normalize(
                Path.GetDirectoryName(added.Path.Replace('/', '\\')) ?? "/");
            if (parent.Equals(directory, StringComparison.OrdinalIgnoreCase))
                existing[added.Name] = added;
        }
        return existing.Values.OrderBy(x => x.Name,
            StringComparer.OrdinalIgnoreCase).ToArray();
    }

    public override int GetSecurityByName(string fileName, out uint attributes,
        ref byte[] securityDescriptor)
    {
        string key = DirectWebDavReadOnlyBackend.Normalize(fileName);
        if (IsOwnLockArtifact(key))
        {
            attributes = 0;
            return NotFound;
        }
        if (_overlay.TryGetValue(key, out var entry))
        {
            attributes = DescribeEntry(entry).FileAttributes &
                ~(uint)System.IO.FileAttributes.ReadOnly;
            if (securityDescriptor is not null)
                securityDescriptor = (byte[])_security.Clone();
            return Success;
        }
        int status = base.GetSecurityByName(fileName, out attributes, ref securityDescriptor);
        if (status >= 0 && (attributes & (uint)System.IO.FileAttributes.Directory) == 0)
        {
            attributes &= ~(uint)System.IO.FileAttributes.ReadOnly;
            attributes |= (uint)System.IO.FileAttributes.Archive;
        }
        return status;
    }

    public override int Create(string fileName, uint createOptions, uint grantedAccess,
        uint fileAttributes, byte[] securityDescriptor, ulong allocationSize,
        out object fileNode, out object fileDesc, out FileInfo info,
        out string normalizedName)
    {
        fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
        string key = DirectWebDavReadOnlyBackend.Normalize(fileName);
        if (IsOwnLockArtifact(key)) return AccessDenied;
        try
        {
            bool directory = (createOptions & 1) != 0;
            if (directory)
            {
                try { _writes.MakeDirectory(key); }
                catch (IOException ex) when (ex.Message.Contains("MKCOL failed HTTP 405",
                    StringComparison.Ordinal))
                {
                    if (_backend.StatFresh(key)?.IsDirectory == true)
                        return Collision;
                    throw;
                }
                var stat = _backend.Stat(key) ??
                    new DirectWebDavReadOnlyBackend.Entry(key,
                        key.Split('/').Last(), true, 0, DateTime.UtcNow);
                fileNode = stat;
                fileDesc = stat;
                info = DescribeEntry(stat);
                RegisterOpenHandle();
                return Success;
            }
            string? lease = AcquireOfficeLease(key);
            DirectWebDavWriteStore.Handle session;
            try { session = _writes.Begin(key, create: true, fastCopy: true); }
            catch { ReleaseOfficeLease(lease); throw; }
            if (allocationSize >= 2UL * 1024 * 1024 &&
                allocationSize <= long.MaxValue)
                _writes.PlanCopy(session, (long)allocationSize);
            if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                Console.Error.WriteLine("DIRECT_RW_NEW_FILE_ALLOCATION bytes=" +
                    allocationSize);
            var entry = new DirectWebDavReadOnlyBackend.Entry(key,
                key.Split('/').Last(), false, 0, DateTime.UtcNow);
            var descriptor = new WriteDescriptor(session, entry);
            descriptor.LeaseKey = lease;
            _overlay[key] = entry;
            fileNode = descriptor;
            fileDesc = descriptor;
            info = Describe(descriptor);
            Interlocked.Increment(ref _activeWriteHandles);
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex) || ex is ArgumentException)
        {
            return Failure(ex, "Create");
        }
    }

    public override int Open(string fileName, uint createOptions, uint grantedAccess,
        out object fileNode, out object fileDesc, out FileInfo info,
        out string normalizedName)
    {
        try
        {
            return OpenCore(fileName, createOptions, grantedAccess, out fileNode,
                out fileDesc, out info, out normalizedName);
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex))
        {
            fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
            return Failure(ex, "OpenTransport");
        }
    }

    private int OpenCore(string fileName, uint createOptions, uint grantedAccess,
        out object fileNode, out object fileDesc, out FileInfo info,
        out string normalizedName)
    {
        if (IsOwnLockArtifact(fileName))
        {
            fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
            return NotFound;
        }
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_OPEN name=" + fileName +
                " access=0x" + grantedAccess.ToString("X") +
                " options=0x" + createOptions.ToString("X"));
        string pendingKey = DirectWebDavReadOnlyBackend.Normalize(fileName);
        if (_pendingCopies.TryGetValue(pendingKey, out var awaiting))
        {
            var entry = awaiting.Writer.Entry with {
                Size = awaiting.Writer.Session.Local.Length
            };
            const uint dataWrite = 0x2u | 0x4u | 0x40000000u;
            fileNode = entry;
            normalizedName = null!;
            info = DescribeEntry(entry);
            if ((grantedAccess & dataWrite) != 0)
            {
                fileDesc = null!;
                return unchecked((int)0xC0000043); // sharing violation
            }
            if ((grantedAccess & (0x100u | 0x10000u | 0x10u)) != 0)
                fileDesc = new AttributeDescriptor(entry);
            else
                fileDesc = new PendingReadDescriptor(entry,
                    new FileStream(awaiting.Writer.Session.LocalPath,
                        FileMode.Open, FileAccess.Read,
                        FileShare.ReadWrite | FileShare.Delete));
            RegisterOpenHandle();
            return Success;
        }
        // Explorer F2/Directory.Move opens an EXISTING directory with
        // DELETE (0x10000) and SYNCHRONIZE (0x100000), but createOptions
        // does not necessarily have FILE_DIRECTORY_FILE (0x1) set.
        // The old condition only checked that flag, then treated the
        // directory as a writable *file* and returned STATUS_ACCESS_DENIED.
        var existingDirectory = _backend.Stat(fileName);
        if ((createOptions & 1u) != 0 || existingDirectory?.IsDirectory == true)
        {
            fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
            var directory = existingDirectory;
            if (directory is null) return NotFound;
            if (!directory.IsDirectory) return AccessDenied;
            fileNode = directory;
            fileDesc = directory;
            info = DescribeEntry(directory);
            RegisterOpenHandle();
            return Success;
        }
        // Windows Explorer and PowerShell commonly open a file with
        // DELETE|READ_ATTRIBUTES (0x10080) before removing or renaming it.
        // DELETE is an operation on the NAME, not a request to edit bytes.
        // The previous WriteIntent.IsWrite() treated DELETE as content-write,
        // then downloaded the entire 1 MB photo and created/fsynced a full
        // recovery journal for EVERY deletion! Return a metadata descriptor.
        const uint contentWrite = 0x2u | 0x4u | 0x40000000u | 0x10000000u;
        if ((grantedAccess & contentWrite) == 0 &&
            (grantedAccess & (0x100u | 0x10000u | 0x10u)) != 0)
        {
            fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
            var attr = _backend.Stat(fileName);
            if (attr is null) return NotFound;
            if (attr.IsDirectory) return AccessDenied;
            fileNode = attr;
            fileDesc = new AttributeDescriptor(attr);
            info = DescribeEntry(attr);
            RegisterOpenHandle();
            return Success;
        }
        if (!WriteIntent.IsWrite(grantedAccess))
            return base.Open(fileName, createOptions, grantedAccess,
                out fileNode, out fileDesc, out info, out normalizedName);
        fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
        string key = DirectWebDavReadOnlyBackend.Normalize(fileName);
        try
        {
            var stat = _backend.Stat(key);
            if (stat is null) return NotFound;
            if (stat.IsDirectory) return AccessDenied;
            string? lease = AcquireOfficeLease(key);
            DirectWebDavWriteStore.Handle session;
            try
            {
                byte[]? ownedHash = null;
                if (ExcelOwnerInterop.IsOwnerFile(key) &&
                    _ownerFilesPublishedHere.TryGetValue(key, out var owner))
                    ownedHash = owner.Sha256;
                session = _writes.Begin(key, create: false,
                    expectedOfficeOwnerHash: ownedHash,
                    allowExcelSnapshot: lease?.StartsWith(ExcelOwnerLeasePrefix,
                        StringComparison.Ordinal) == true);
            }
            catch { ReleaseOfficeLease(lease); throw; }
            var descriptor = new WriteDescriptor(session, stat);
            descriptor.LeaseKey = lease;
            fileNode = descriptor;
            fileDesc = descriptor;
            info = Describe(descriptor);
            Interlocked.Increment(ref _activeWriteHandles);
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex) || ex is ArgumentException)
        {
            return Failure(ex, "Open");
        }
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
        if (fileDesc is PendingReadDescriptor pending)
        {
            transferred = 0;
            if (offset >= (ulong)pending.Stream.Length) return EndOfFile;
            if (length > 4 * 1024 * 1024) return IoError;
            try
            {
                var bytes = new byte[length];
                int count;
                lock (pending.Stream)
                {
                    pending.Stream.Seek((long)offset, SeekOrigin.Begin);
                    count = pending.Stream.Read(bytes, 0, bytes.Length);
                }
                if (count != 0) Marshal.Copy(bytes, 0, buffer, count);
                transferred = (uint)count;
                return Success;
            }
            catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "ReadPending"); }
        }
        if (fileDesc is not WriteDescriptor writer)
            return base.Read(fileNode, fileDesc, buffer, offset, length, out transferred);
        transferred = 0;
        if (offset >= (ulong)writer.Session.Local.Length) return EndOfFile;
        if (length > 4 * 1024 * 1024) return IoError;
        try
        {
            var temp = new byte[length];
            int read = _writes.Read(writer.Session, (long)offset, temp);
            if (read > 0) Marshal.Copy(temp, 0, buffer, read);
            transferred = (uint)read;
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "Read"); }
    }

    public override int Write(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, bool writeToEndOfFile, bool constrainedIo,
        out uint transferred, out FileInfo info)
    {
        transferred = 0;
        info = default;
        if (fileDesc is not WriteDescriptor writer) return AccessDenied;
        if (length > 4 * 1024 * 1024) return IoError;
        try
        {
            if (constrainedIo && offset >= (ulong)writer.Session.Local.Length)
            {
                info = Describe(writer);
                return Success;
            }
            ulong allowed = constrainedIo
                ? Math.Min(length, (ulong)writer.Session.Local.Length - offset) : length;
            byte[] data = new byte[(int)allowed];
            if (data.Length > 0) Marshal.Copy(buffer, data, 0, data.Length);
            _writes.Write(writer.Session, offset, data, writeToEndOfFile);
            if (ExcelOwnerInterop.IsOwnerFile(writer.Entry.Path) &&
                writer.Session.Dirty)
            {
                // Office keeps this handle open. A real server-confirmed
                // owner must exist during editing, even on NASes that
                // supply neither PUT nor PROPFIND/GET ETags.
                _writes.Commit(writer.Session);
                RecordCommittedRevision(writer);
                _overlay.TryRemove(writer.Entry.Path, out _);
                PublishExcelOwnerRevision(writer);
            }
            transferred = (uint)data.Length;
            info = Describe(writer);
            _overlay[writer.Entry.Path] = writer.Entry with { Size = writer.Session.Local.Length };
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex) || ex is ArgumentException)
        {
            return Failure(ex, "Write");
        }
    }

    public override int Flush(object fileNode, object fileDesc, out FileInfo info)
    {
        info = default;
        if (fileDesc is not WriteDescriptor writer) return Success;
        try
        {
            if (writer.Session.WriteBehind && writer.Session.Created)
            {
                _writes.FlushStaging(writer.Session);
                info = Describe(writer);
                return Success;
            }
            _writes.Commit(writer.Session);
            _overlay.TryRemove(writer.Entry.Path, out _);
            // After our own successful conditional PUT, refresh the
            // descriptor exactly once from the server's committed revision.
            RecordCommittedRevision(writer);
            info = Describe(writer);
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "Flush"); }
    }

    public override int GetFileInfo(object fileNode, object fileDesc, out FileInfo info)
    {
        if (fileDesc is PendingReadDescriptor queued)
        {
            info = DescribeEntry(queued.Entry);
            return Success;
        }
        if (fileDesc is AttributeDescriptor attribute)
        {
            info = DescribeEntry(attribute.Entry);
            return Success;
        }
        if (fileDesc is WriteDescriptor writer)
        {
            info = Describe(writer);
            return Success;
        }
        return base.GetFileInfo(fileNode, fileDesc, out info);
    }

    public override int Overwrite(object fileNode, object fileDesc, uint fileAttributes,
        bool replaceFileAttributes, ulong allocationSize, out FileInfo info)
    {
        info = default;
        if (fileDesc is not WriteDescriptor writer) return AccessDenied;
        try { _writes.SetLength(writer.Session, 0); info = Describe(writer); return Success; }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "Overwrite"); }
    }

    public override int SetFileSize(object fileNode, object fileDesc,
        ulong newSize, bool setAllocationSize, out FileInfo info)
    {
        info = default;
        if (fileDesc is not WriteDescriptor writer || newSize > long.MaxValue)
            return AccessDenied;
        try
        {
            if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                Console.Error.WriteLine("DIRECT_RW_SET_FILE_SIZE bytes=" +
                    newSize + " allocationOnly=" + setAllocationSize);
            if (!setAllocationSize || (ulong)writer.Session.Local.Length > newSize)
                _writes.SetLength(writer.Session, (long)newSize);
            else if (setAllocationSize && newSize >= 2UL * 1024 * 1024)
                _writes.PlanCopy(writer.Session, (long)newSize);
            info = Describe(writer);
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "SetFileSize"); }
    }

    public override int SetBasicInfo(object fileNode, object fileDesc,
        uint attributes, ulong creationTime, ulong lastAccessTime,
        ulong lastWriteTime, ulong changeTime, out FileInfo info)
    {
        info = default;
        if (fileDesc is DirectWebDavReadOnlyBackend.Entry directory &&
            directory.IsDirectory)
        {
            // PowerShell Remove-Item -Recurse -Force first clears folder
            // attributes using FILE_WRITE_ATTRIBUTES before asking WinFsp
            // to delete the now-empty folder. Failing this harmless local
            // metadata operation made Windows abort BEFORE CanDelete or
            // CleanupDelete, so files disappeared but their folder survived.
            info = DescribeEntry(directory);
            return Success;
        }
        if (fileDesc is AttributeDescriptor attribute)
        {
            if (lastWriteTime is > 0 and < long.MaxValue)
            {
                try
                {
                    attribute.Entry = attribute.Entry with {
                        ModifiedUtc = DateTime.FromFileTimeUtc((long)lastWriteTime)
                    };
                }
                catch (ArgumentOutOfRangeException) { return AccessDenied; }
            }
            info = DescribeEntry(attribute.Entry);
            return Success;
        }
        if (fileDesc is not WriteDescriptor writer) return AccessDenied;
        // Excel sets Win32 timestamps while writing its temporary document.
        // STATUS_NOT_SUPPORTED here caused Excel to report the workbook as
        // unsaved after it had issued its write operations. WebDAV has fewer
        // metadata fields than NTFS, so retain the requested last-write time
        // consistently on this open descriptor. No remote ETag is invented.
        if (lastWriteTime is > 0 and < long.MaxValue)
        {
            try
            {
                writer.Entry = writer.Entry with {
                    ModifiedUtc = DateTime.FromFileTimeUtc((long)lastWriteTime)
                };
            }
            catch (ArgumentOutOfRangeException) { return AccessDenied; }
        }
        info = Describe(writer);
        return Success;
    }

    public override int CanDelete(object fileNode, object fileDesc, string fileName)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CAN_DELETE name=" + fileName);
        string key = fileDesc switch {
            WriteDescriptor writer => writer.Entry.Path,
            DirectWebDavReadOnlyBackend.Entry entry => entry.Path,
            AttributeDescriptor attribute => attribute.Entry.Path,
            _ => DirectWebDavReadOnlyBackend.Normalize(fileName)
        };
        if (key == "/") return AccessDenied;
        if (_pendingCopies.ContainsKey(key)) return Success;
        try
        {
            var entry = _backend.StatFresh(key);
            if (entry?.IsDirectory == true &&
                _backend.RefreshList(key).Length != 0)
                return unchecked((int)0xC0000101); // STATUS_DIRECTORY_NOT_EMPTY
            // Explicit user deletes use fresh path resolution, not an ETag
            // which Synology can change after writing.
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "CanDelete"); }
        return Success;
    }

    public override int Rename(object fileNode, object fileDesc, string fileName,
        string newFileName, bool replaceIfExists)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_RENAME from=" + fileName +
                " to=" + newFileName + " replace=" + replaceIfExists);
        // An existing destination is replaced using a conditional PUT and
        // verified checksum, never an unsafe MOVE Overwrite:T.
        string from = fileDesc switch {
            WriteDescriptor writer => writer.Entry.Path,
            DirectWebDavReadOnlyBackend.Entry entry => entry.Path,
            AttributeDescriptor metadata => metadata.Entry.Path,
            _ => DirectWebDavReadOnlyBackend.Normalize(fileName)
        };
        string to = DirectWebDavReadOnlyBackend.Normalize(newFileName);
        if (_pendingCopies.TryGetValue(from, out var queuedRename))
        {
            if (!queuedRename.Completion.Task.Wait(TimeSpan.FromMinutes(3)) ||
                !queuedRename.Completion.Task.Result)
                return IoError;
        }
        string? sourceLease = null;
        string? destinationLease = null;
        try
        {
            if (!from.Equals(to, StringComparison.OrdinalIgnoreCase))
            {
                // The old workbook -> backup and temp -> final Excel renames
                // must acquire their respective source/destination lock
                // before any server-side MOVE is attempted.
                sourceLease = AcquireOfficeLease(from);
                destinationLease = AcquireOfficeLease(to);
            }
            if (fileDesc is WriteDescriptor writer)
            {
                _writes.Commit(writer.Session);
                RecordCommittedRevision(writer);
            }
            var original = _backend.StatFresh(from);
            if (original is null) return NotFound;
            if (original.IsDirectory)
                _writes.RenameDirectory(from, to, original.ETag, replaceIfExists);
            else
                _writes.RenameWithVerifiedReplacement(from, to,
                    original.ETag, replaceIfExists);
            _overlay.TryRemove(from, out _);
            if (fileDesc is WriteDescriptor active)
            {
                var newStat = _backend.Stat(to);
                _writes.UpdateRenamedHandle(active.Session, to, newStat);
                if (newStat is not null)
                {
                    active.Entry = newStat;
                }
            }
            return Success;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { return Failure(ex, "Rename"); }
        finally
        {
            ReleaseOfficeLease(destinationLease);
            ReleaseOfficeLease(sourceLease);
        }
    }

    public override void Cleanup(object fileNode, object fileDesc,
        string fileName, uint flags)
    {
        try { CleanupOfficeAware(fileNode, fileDesc, fileName, flags); }
        finally { ReleaseExcelOwnerSessionAtCleanup(fileDesc); }
    }

    private void CleanupOfficeAware(object fileNode, object fileDesc,
        string fileName, uint flags)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CLEANUP path=" + fileName + " flags=" + flags);
        if ((flags & CleanupDelete) == 0)
        {
            // WinFsp Cleanup happens when the final Windows FILE HANDLE is
            // closed. Close() may be delayed for cache-manager references,
            // so waiting until Close can leave a newly created file missing
            // from the server even after File.WriteAllText() returned.
            if (fileDesc is WriteDescriptor dirty && dirty.Session.Dirty)
            {
                try
                {
                    if (dirty.Session.WriteBehind && dirty.Session.Created &&
                        QueueCopy(dirty))
                    {
                        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                            Console.Error.WriteLine("DIRECT_RW_CLEANUP_QUEUED path=" + fileName);
                        return;
                    }
                    // Random writes/truncation are legal file operations,
                    // not a failed sequential copy. Commit in this callback
                    // instead of leaving the temp file for delayed Close.
                    _writes.Commit(dirty.Session);
                    RecordCommittedRevision(dirty);
                    _overlay.TryRemove(dirty.Entry.Path, out _);
                    if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                        Console.Error.WriteLine("DIRECT_RW_CLEANUP_COMMIT_OK path=" + fileName);
                }
                catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { Failure(ex, "CleanupCommit"); }
            }
            return;
        }
        // The managed WinFsp Cleanup callback may pass an EMPTY fileName.
        // Use the already validated descriptor key, never interpret empty
        // as NAS root or issue DELETE against the wrong remote object.
        string key = fileDesc switch {
            WriteDescriptor writer => writer.Entry.Path,
            DirectWebDavReadOnlyBackend.Entry entry => entry.Path,
            AttributeDescriptor metadata => metadata.Entry.Path,
            _ => DirectWebDavReadOnlyBackend.Normalize(fileName)
        };
        try
        {
            if (_pendingCopies.TryGetValue(key, out var queuedDelete))
            {
                Interlocked.Exchange(ref queuedDelete.DeleteRequested, 1);
                if (!queuedDelete.Completion.Task.Wait(TimeSpan.FromMinutes(3)) ||
                    !queuedDelete.Completion.Task.Result)
                    throw new IOException("Queued upload could not be canceled or deleted");
            }
            if (fileDesc is WriteDescriptor writer && writer.Session.Created)
            {
                writer.Deleted = true;
                _overlay.TryRemove(key, out _);
                return; // uncommitted new file was never on NAS
            }
            var existing = _backend.StatFresh(key);
            if (existing is not null)
            {
                bool wasDirectory = fileDesc is
                    DirectWebDavReadOnlyBackend.Entry { IsDirectory: true };
                if (existing.IsDirectory != wasDirectory)
                    throw new IOException("Delete target changed resource type");
                _writes.Delete(key, existing.ETag, existing.IsDirectory,
                    explicitUserDelete: true);
            }
            if (ExcelOwnerInterop.IsOwnerFile(key))
                _ownerFilesPublishedHere.TryRemove(key, out _);
            if (fileDesc is WriteDescriptor owned) owned.Deleted = true;
            _overlay.TryRemove(key, out _);
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex)) { Failure(ex, "CleanupDelete"); }
    }

    public override void Close(object fileNode, object fileDesc)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CLOSE type=" + fileDesc?.GetType().Name);
        if (fileDesc is PendingReadDescriptor pendingRead)
        {
            pendingRead.Stream.Dispose();
            base.Close(fileNode, pendingRead.Entry);
            return;
        }
        if (fileDesc is AttributeDescriptor attribute)
        {
            base.Close(fileNode, attribute.Entry);
            return;
        }
        if (fileDesc is not WriteDescriptor writer)
        {
            base.Close(fileNode, fileDesc);
            return;
        }
        if (writer.Queued)
        {
            writer.Closed = true;
            return; // uploader owns session and releases the active lease
        }
        if (writer.Closed) return;
        writer.Closed = true;
        try
        {
            if (writer.Deleted)
                _writes.Discard(writer.Session);
            else if (writer.LeaseKey is null &&
                _writes.RetainSupersededOfficeClose(writer.Session))
            {
                // A late cache-manager Close belongs to a superseded
                // revision. Its journal is retained; do not touch NAS.
                _backend.Invalidate(writer.Entry.Path);
            }
            else
                _writes.Close(writer.Session, commit: true);
            _overlay.TryRemove(writer.Entry.Path, out _);
            ReleaseOfficeLease(writer.LeaseKey);
            writer.LeaseKey = null;
        }
        catch (Exception ex) when (DirectWebDavTransport.IsIoFailure(ex))
        {
            // The session's local journal is retained. NEVER pretend that
            // failure to upload at Close means the NAS saved this document.
            Failure(ex, "Close");
            _overlay.TryRemove(writer.Entry.Path, out _);
            _backend.Invalidate(writer.Entry.Path);
            try { writer.Session.Dispose(); } catch (IOException) { }
        }
        finally
        {
            if (writer.LeaseKey is not null)
            {
                ReleaseOfficeLease(writer.LeaseKey);
                writer.LeaseKey = null;
            }
            Interlocked.Decrement(ref _activeWriteHandles);
        }
    }

    public override int GetSecurity(object fileNode, object fileDesc,
        ref byte[] securityDescriptor)
    {
        if (fileDesc is not WriteDescriptor)
            return base.GetSecurity(fileNode, fileDesc, ref securityDescriptor);
        securityDescriptor = (byte[])_security.Clone();
        return Success;
    }
}
