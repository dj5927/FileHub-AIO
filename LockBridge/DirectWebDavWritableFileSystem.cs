using System.Collections.Concurrent;
using System.Runtime.InteropServices;
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
    private readonly ConcurrentDictionary<string, string> _ownerFilesPublishedHere =
        new(StringComparer.OrdinalIgnoreCase);
    private const string ExcelOwnerLeasePrefix = "excel-owner-only:";
    private int _activeWriteHandles;
    internal int ActiveWriteHandles => Volatile.Read(ref _activeWriteHandles);
    // Explorer may retain read-only metadata handles for icon caching.
    // Only writers, unfinished uploads and held leases block safe unmount.
    internal bool CanShutdown => ActiveWriteHandles == 0 &&
        (_leases?.CanShutdown ?? true) && !_writes.HasUncommittedRecovery;
    private readonly ConcurrentDictionary<string, DirectWebDavReadOnlyBackend.Entry> _overlay =
        new(StringComparer.OrdinalIgnoreCase);
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

    internal DirectWebDavWritableFileSystem(DirectWebDavReadOnlyBackend backend,
        DirectWebDavWriteStore store, string aclDirectory,
        LeaseCoordinator? leases = null) : base(backend, aclDirectory)
    {
        _writes = store;
        _leases = leases;
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
                            DirectWebDavWriteStore.StrongEtag(remoteEtag) &&
                            _ownerFilesPublishedHere.TryGetValue(ownerPath,
                                out string? ourEtag) &&
                            string.Equals(remoteEtag, ourEtag,
                                StringComparison.Ordinal));
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
                _writes.MakeDirectory(key);
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
            try { session = _writes.Begin(key, create: true); }
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
        catch (Exception ex) when (ex is IOException or ArgumentException)
        {
            return Failure(ex, "Create");
        }
    }

    public override int Open(string fileName, uint createOptions, uint grantedAccess,
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
        // An Excel attribute-only handle does not need to download the
        // entire workbook a second time or create a recovery journal.
        if (!WriteIntent.IsWrite(grantedAccess) &&
            (grantedAccess & 0x100u) != 0)
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
            try { session = _writes.Begin(key, create: false); }
            catch { ReleaseOfficeLease(lease); throw; }
            var descriptor = new WriteDescriptor(session, stat);
            descriptor.LeaseKey = lease;
            fileNode = descriptor;
            fileDesc = descriptor;
            info = Describe(descriptor);
            Interlocked.Increment(ref _activeWriteHandles);
            return Success;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        {
            return Failure(ex, "Open");
        }
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
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
        catch (IOException ex) { return Failure(ex, "Read"); }
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
                // Excel writes its own binary owner file. Publish the bytes
                // to the NAS before acknowledging its WinFsp Write, rather
                // than waiting for eventual Cleanup (which left RaiDrive
                // unaware of an editor). No synthetic owner file is made.
                _writes.Commit(writer.Session);
                _overlay.TryRemove(writer.Entry.Path, out _);
                RecordCommittedRevision(writer);
                if (DirectWebDavWriteStore.StrongEtag(writer.Session.ExpectedEtag))
                    _ownerFilesPublishedHere[writer.Entry.Path] =
                        writer.Session.ExpectedEtag!;
                Console.WriteLine("DIRECT_EXCEL_OWNER_REMOTE_VISIBLE");
            }
            transferred = (uint)data.Length;
            info = Describe(writer);
            _overlay[writer.Entry.Path] = writer.Entry with { Size = writer.Session.Local.Length };
            return Success;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
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
            _writes.Commit(writer.Session);
            _overlay.TryRemove(writer.Entry.Path, out _);
            // After our own successful conditional PUT, refresh the
            // descriptor exactly once from the server's committed revision.
            RecordCommittedRevision(writer);
            info = Describe(writer);
            return Success;
        }
        catch (IOException ex) { return Failure(ex, "Flush"); }
    }

    public override int GetFileInfo(object fileNode, object fileDesc, out FileInfo info)
    {
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
        catch (IOException ex) { return Failure(ex, "Overwrite"); }
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
        catch (IOException ex) { return Failure(ex, "SetFileSize"); }
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
        try
        {
            var entry = _backend.Stat(key);
            if (entry?.IsDirectory == true &&
                _backend.RefreshList(key).Length != 0)
                return unchecked((int)0xC0000101); // STATUS_DIRECTORY_NOT_EMPTY
            if (entry is { IsDirectory:false } &&
                !DirectWebDavWriteStore.StrongEtag(entry.ETag))
                return AccessDenied;
        }
        catch (IOException ex) { return Failure(ex, "CanDelete"); }
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
            _ => DirectWebDavReadOnlyBackend.Normalize(fileName)
        };
        string to = DirectWebDavReadOnlyBackend.Normalize(newFileName);
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
            var original = _backend.Stat(from);
            if (original is null) return NotFound;
            if (original.IsDirectory)
                _writes.RenameDirectory(from, to, original.ETag, replaceIfExists);
            else
                _writes.RenameWithVerifiedReplacement(from, to,
                    original.ETag, replaceIfExists);
            _overlay.TryRemove(from, out _);
            if (fileDesc is WriteDescriptor active)
            {
                active.Session.RemotePath = to;
                var newStat = _backend.Stat(to);
                if (newStat is not null)
                {
                    active.Entry = newStat;
                    active.Session.ExpectedEtag = newStat.ETag;
                }
            }
            return Success;
        }
        catch (IOException ex) { return Failure(ex, "Rename"); }
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
                    _writes.Commit(dirty.Session);
                    RecordCommittedRevision(dirty);
                    _overlay.TryRemove(dirty.Entry.Path, out _);
                    if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                        Console.Error.WriteLine("DIRECT_RW_CLEANUP_COMMIT_OK path=" + fileName);
                }
                catch (IOException ex) { Failure(ex, "CleanupCommit"); }
            }
            return;
        }
        // The managed WinFsp Cleanup callback may pass an EMPTY fileName.
        // Use the already validated descriptor key, never interpret empty
        // as NAS root or issue DELETE against the wrong remote object.
        string key = fileDesc switch {
            WriteDescriptor writer => writer.Entry.Path,
            DirectWebDavReadOnlyBackend.Entry entry => entry.Path,
            _ => DirectWebDavReadOnlyBackend.Normalize(fileName)
        };
        try
        {
            if (fileDesc is WriteDescriptor writer && writer.Session.Created)
            {
                writer.Deleted = true;
                _overlay.TryRemove(key, out _);
                return; // uncommitted new file was never on NAS
            }
            var existing = _backend.Stat(key);
            if (existing is not null)
                _writes.Delete(key, existing.ETag, existing.IsDirectory);
            if (ExcelOwnerInterop.IsOwnerFile(key))
                _ownerFilesPublishedHere.TryRemove(key, out _);
            if (fileDesc is WriteDescriptor owned) owned.Deleted = true;
            _overlay.TryRemove(key, out _);
        }
        catch (IOException ex) { Failure(ex, "CleanupDelete"); }
    }

    public override void Close(object fileNode, object fileDesc)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CLOSE type=" + fileDesc?.GetType().Name);
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
        if (writer.Closed) return;
        writer.Closed = true;
        try
        {
            if (writer.Deleted)
                _writes.Discard(writer.Session);
            else
                _writes.Close(writer.Session, commit: true);
            _overlay.TryRemove(writer.Entry.Path, out _);
            ReleaseOfficeLease(writer.LeaseKey);
            writer.LeaseKey = null;
        }
        catch (IOException ex)
        {
            // The session's local journal is retained. NEVER pretend that
            // failure to upload at Close means the NAS saved this document.
            Failure(ex, "Close");
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
