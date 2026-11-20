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
    private int _activeWriteHandles;
    internal int ActiveWriteHandles => Volatile.Read(ref _activeWriteHandles);
    // Explorer may retain read-only metadata handles for icon caching.
    // Only writers, unfinished uploads and held leases block safe unmount.
    internal bool CanShutdown => ActiveWriteHandles == 0 &&
        (_leases?.CanShutdown ?? true) && !_writes.HasUncommittedRecovery;
    private readonly ConcurrentDictionary<string, DirectWebDavReadOnlyBackend.Entry> _overlay =
        new(StringComparer.OrdinalIgnoreCase);

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

    internal DirectWebDavWritableFileSystem(DirectWebDavReadOnlyBackend backend,
        DirectWebDavWriteStore store, string aclDirectory,
        LeaseCoordinator? leases = null) : base(backend, aclDirectory)
    {
        _writes = store;
        _leases = leases;
    }

    private string? AcquireOfficeLease(string path)
    {
        if (_leases is null || !WriteIntent.IsDocument(path)) return null;
        string key = path.TrimStart('/').Replace('\\', '/');
        _leases.AddHandle(key);
        return key;
    }

    private void ReleaseOfficeLease(string? key)
    {
        if (key is not null) _leases?.ReleaseHandle(key);
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
        var local = descriptor.Entry with {
            Size = descriptor.Session.Local.Length,
            ModifiedUtc = DateTime.UtcNow
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

    protected override DirectWebDavReadOnlyBackend.Entry[] ListForDirectory(string directory)
    {
        var remote = _backend.List(directory);
        var existing = remote.Where(x =>
            !x.Name.EndsWith(".nasmount.lock", StringComparison.OrdinalIgnoreCase) &&
            !x.Name.StartsWith(".nasmount-stage-", StringComparison.OrdinalIgnoreCase))
            .ToDictionary(x => x.Name,
            StringComparer.OrdinalIgnoreCase);
        foreach (var added in _overlay.Values)
        {
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
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_OPEN name=" + fileName +
                " access=0x" + grantedAccess.ToString("X") +
                " options=0x" + createOptions.ToString("X"));
        // Windows opens the containing DIRECTORY with DELETE/WRITE access
        // during MoveFileEx. The read-only base class rejects such access;
        // a writable volume must open the DIRECTORY as a directory handle.
        if ((createOptions & 1u) != 0)
        {
            fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
            var directory = _backend.Stat(fileName);
            if (directory is null) return NotFound;
            if (!directory.IsDirectory) return AccessDenied;
            fileNode = directory;
            fileDesc = directory;
            info = DescribeEntry(directory);
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
            info = Describe(writer);
            return Success;
        }
        catch (IOException ex) { return Failure(ex, "Flush"); }
    }

    public override int GetFileInfo(object fileNode, object fileDesc, out FileInfo info)
    {
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
            if (!setAllocationSize || (ulong)writer.Session.Local.Length > newSize)
                _writes.SetLength(writer.Session, (long)newSize);
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
        if (fileDesc is not WriteDescriptor writer) return AccessDenied;
        // The backend must not lie that WebDAV supports persistent attributes.
        if (creationTime != 0 || lastAccessTime != 0 || lastWriteTime != 0 ||
            changeTime != 0) return unchecked((int)0xC00000BB); // NOT_SUPPORTED
        info = Describe(writer);
        return Success;
    }

    public override int CanDelete(object fileNode, object fileDesc, string fileName)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CAN_DELETE name=" + fileName);
        return Success;
    }

    public override int Rename(object fileNode, object fileDesc, string fileName,
        string newFileName, bool replaceIfExists)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_RENAME from=" + fileName +
                " to=" + newFileName + " replace=" + replaceIfExists);
        // Windows may set replaceIfExists=true even when the destination is
        // absent. Forward the rename ONLY with HTTP Overwrite:F, which
        // rejects an occupied destination regardless of this Windows flag.
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
                _writes.Commit(writer.Session);
            var original = _backend.Stat(from);
            if (original is null) return NotFound;
            _writes.RenameNewDestination(from, to, original.ETag);
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
                _writes.Delete(key, existing.ETag);
            if (fileDesc is WriteDescriptor owned) owned.Deleted = true;
            _overlay.TryRemove(key, out _);
        }
        catch (IOException ex) { Failure(ex, "CleanupDelete"); }
    }

    public override void Close(object fileNode, object fileDesc)
    {
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_RW_CLOSE type=" + fileDesc?.GetType().Name);
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
