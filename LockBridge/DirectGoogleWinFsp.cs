using System.Collections.Concurrent;
using System.IO.Enumeration;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using Fsp;
using Fsp.Interop;
using FspFileInfo = Fsp.Interop.FileInfo;
using FspVolumeInfo = Fsp.Interop.VolumeInfo;

namespace NASMount.LockBridge;

/// <summary>
/// First real WinFsp provider for Google Drive without rclone. Deliberately
/// launched via explicit pilot command, NOT yet default Google profiles:
/// only native file reads and folder operations are enabled while full
/// Office write/revision conflict recovery is under separate validation.
/// </summary>
internal sealed class DirectGoogleWinFsp : FileSystemBase, IDisposable
{
    private const int Ok = 0;
    private const int Missing = unchecked((int)0xC0000034);
    private const int Denied = unchecked((int)0xC0000022);
    private const int IoError = unchecked((int)0xC0000185);
    private const int Eof = unchecked((int)0xC0000011);
    private const int Exists = unchecked((int)0xC0000035);
    private const int Unsupported = unchecked((int)0xC00000BB);
    private readonly GoogleDirectApiClient _api;
    private readonly byte[] _acl;
    private readonly bool _allowFolderMutations;
    private readonly string _stageRoot;
    private int _opened;
    internal int OpenedHandles => Volatile.Read(ref _opened);
    internal int PendingRecoveryCount => Directory.Exists(_stageRoot)
        ? Directory.EnumerateFiles(_stageRoot, "*.json").Count()
        : 0;

    internal sealed record Entry(string Path, string Name, string Id,
        bool Directory, long Size, DateTime Modified, string Mime, string? Version);
    private sealed class Cursor(Entry[] items, string? marker)
    {
        internal Entry[] Items = items;
        internal int Index = marker is null ? 0 :
            Math.Max(0, Array.FindIndex(items, item =>
                item.Name.Equals(marker, StringComparison.OrdinalIgnoreCase)) + 1);
    }
    private readonly ConcurrentDictionary<string, Entry> _recent = new(
        StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DateTimeOffset> _recentUntil = new(
        StringComparer.OrdinalIgnoreCase);
    private static readonly TimeSpan EntryTtl = TimeSpan.FromSeconds(30);
    private const int ReadBlockSize = 1024 * 1024;
    private sealed record ReadBlock(byte[] Bytes, DateTimeOffset Expires);
    private readonly ConcurrentDictionary<string, Lazy<Task<ReadBlock>>> _readCache =
        new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DirectGoogleWriteStage> _newFiles = new(
        StringComparer.OrdinalIgnoreCase);
    private sealed record NewFile(Entry Entry, DirectGoogleWriteStage Stage)
    {
        internal int Cleaned;
    }

    internal DirectGoogleWinFsp(GoogleDirectApiClient api,
        string localAclDirectory, bool allowFolderMutations,
        string? stageDirectory = null)
    {
        _api = api;
        _allowFolderMutations = allowFolderMutations;
        _acl = new DirectoryInfo(localAclDirectory).GetAccessControl()
            .GetSecurityDescriptorBinaryForm();
        _stageRoot = stageDirectory ?? Path.Combine(
            AppStore.DirectoryPath, "google-direct-stages");
        // Pilot is read-only for files; no recovery directory or file
        // content is allocated until a future safe-write phase is enabled.
    }

    private static string Canonical(string p)
    {
        var parts = p.Replace('\\', '/')
            .Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (parts.Any(part => part is "." or ".." ||
            part.Contains(':') || part.IndexOf('\0') >= 0))
            throw new ArgumentException("Invalid Google WinFsp path");
        return "/" + string.Join('/', parts);
    }

    private static string Parent(string p)
    {
        string normalized = Canonical(p);
        int pos = normalized.LastIndexOf('/');
        return pos <= 0 ? "/" : normalized[..pos];
    }

    private static Entry FromItem(string parent, GoogleDirectApiClient.Item item)
    {
        string path = (parent == "/" ? "" : parent) + "/" + item.Name;
        var modified = item.Modified?.UtcDateTime ?? DateTime.UnixEpoch;
        return new Entry(path, item.Name, item.Id, item.IsFolder,
            item.Size, modified, item.MimeType, item.Version);
    }

    internal Entry? Find(string path)
    {
        path = Canonical(path);
        if (path == "/")
            return new Entry("/", "", "root", true, 0,
                DateTime.UnixEpoch, GoogleDirectApiClient.FolderMime, null);
        if (_newFiles.TryGetValue(path, out var pending))
            return new Entry(path, path[(path.LastIndexOf('/') + 1)..],
                "local", false, pending.Length, DateTime.UtcNow,
                "application/octet-stream", null);
        if (_recent.TryGetValue(path, out var cached) &&
            _recentUntil.TryGetValue(path, out var until) &&
            until > DateTimeOffset.UtcNow) return cached;
        _recent.TryRemove(path, out _);
        _recentUntil.TryRemove(path, out _);
        Entry parent = Find(Parent(path)) ??
            throw new FileNotFoundException("Google directory parent not found");
        if (!parent.Directory) return null;
        string component = path[(path.LastIndexOf('/') + 1)..];
        var list = _api.ListChildrenAsync(parent.Id).GetAwaiter().GetResult();
        var found = list.FirstOrDefault(e =>
            string.Equals(e.Name, component, StringComparison.Ordinal)) ??
            list.FirstOrDefault(e =>
                string.Equals(e.Name, component, StringComparison.OrdinalIgnoreCase));
        if (found is null) return null;
        var entry = FromItem(parent.Path, found);
        _recent[entry.Path] = entry;
        _recentUntil[entry.Path] = DateTimeOffset.UtcNow + EntryTtl;
        return entry;
    }

    internal Entry[] List(string parentPath)
    {
        var parent = Find(parentPath) ?? throw new DirectoryNotFoundException();
        if (!parent.Directory) throw new IOException("Path is not a directory");
        var list = _api.ListChildrenAsync(parent.Id).GetAwaiter().GetResult();
        var items = list.Select(item => FromItem(parent.Path, item))
            .OrderBy(entry => entry.Name, StringComparer.OrdinalIgnoreCase)
            .ToArray();
        var newItems = _newFiles.Where(pair => Parent(pair.Key) == parent.Path)
            .Select(pair => new Entry(pair.Key,
                pair.Key[(pair.Key.LastIndexOf('/') + 1)..],
                "local", false, pair.Value.Length, DateTime.UtcNow,
                "application/octet-stream", null)).ToArray();
        if (newItems.Length > 0)
            items = items.Concat(newItems).OrderBy(item => item.Name,
                StringComparer.OrdinalIgnoreCase).ToArray();
        foreach (var item in items)
        {
            _recent[item.Path] = item;
            _recentUntil[item.Path] = DateTimeOffset.UtcNow + EntryTtl;
        }
        return items;
    }

    private static FspFileInfo Describe(Entry entry)
    {
        FspFileInfo result = default;
        bool readonlyContent = entry.Mime.StartsWith(
            "application/vnd.google-apps.", StringComparison.OrdinalIgnoreCase)
            && !entry.Directory;
        result.FileAttributes = (uint)(entry.Directory ?
            System.IO.FileAttributes.Directory :
            readonlyContent ? System.IO.FileAttributes.ReadOnly :
            System.IO.FileAttributes.Normal);
        result.FileSize = entry.Directory ? 0 : (ulong)Math.Max(0, entry.Size);
        result.AllocationSize = (result.FileSize + 4095) / 4096 * 4096;
        ulong date = (ulong)(entry.Modified <= DateTime.UnixEpoch ?
            DateTime.UnixEpoch : entry.Modified).ToFileTimeUtc();
        result.CreationTime = date;
        result.LastAccessTime = date;
        result.LastWriteTime = date;
        result.ChangeTime = date;
        return result;
    }

    private static FspFileInfo Describe(NewFile staged) =>
        Describe(staged.Entry with { Size = staged.Stage.Length,
            Modified = DateTime.UtcNow });

    // Explorer, image previews and Office perform dozens of small reads
    // against the same file. Merge them into 1 MiB ranged Google API reads
    // and reuse each block for 30 seconds. This does not trigger a Drive
    // About()/quota call and therefore doesn't delay a cold first open.
    private byte[] ReadCached(Entry entry, long offset, int count)
    {
        if (count <= 0) return [];
        byte[] output = new byte[count];
        int copied = 0;
        while (copied < count)
        {
            long current = offset + copied;
            long blockIndex = current / ReadBlockSize;
            long blockStart = blockIndex * ReadBlockSize;
            int fetch = (int)Math.Min(ReadBlockSize, entry.Size - blockStart);
            if (fetch <= 0) break;
            string key = entry.Id + "|" + entry.Version + "|" + blockIndex;
            if (_readCache.Count > 64) _readCache.Clear();
            var factory = _readCache.GetOrAdd(key,
                _ => new Lazy<Task<ReadBlock>>(async () =>
                {
                    byte[] bytes = await _api.ReadRangeAsync(entry.Id,
                        blockStart, fetch).ConfigureAwait(false);
                    return new ReadBlock(bytes, DateTimeOffset.UtcNow.AddSeconds(30));
                }, LazyThreadSafetyMode.ExecutionAndPublication));
            ReadBlock block;
            try { block = factory.Value.GetAwaiter().GetResult(); }
            catch
            {
                _readCache.TryRemove(key, out _);
                throw;
            }
            if (block.Expires <= DateTimeOffset.UtcNow)
            {
                _readCache.TryRemove(key, out _);
                continue;
            }
            int begin = checked((int)(current - blockStart));
            if (begin >= block.Bytes.Length) break;
            int copy = Math.Min(count - copied, block.Bytes.Length - begin);
            Buffer.BlockCopy(block.Bytes, begin, output, copied, copy);
            copied += copy;
        }
        return copied == output.Length ? output : output[..copied];
    }

    private static int Failure(Exception ex, string operation)
    {
        // Never put Drive IDs, file/folder names, tokens or URLs in logs.
        Console.Error.WriteLine("DIRECT_GOOGLE_WINFSP_FAILED operation=" +
            operation + " category=" +
            (ex is FileNotFoundException ? "not_found" :
             ex is GoogleDirectApiClient.GoogleDirectApiException api ?
                 "google_http_" + api.HttpStatus :
             ex is UnauthorizedAccessException ? "permission" :
             ex is OperationCanceledException ? "timeout" :
             ex.GetType().Name));
        return ex is FileNotFoundException or DirectoryNotFoundException ?
            Missing : ex is UnauthorizedAccessException ? Denied : IoError;
    }

    public override int Init(object hostObject)
    {
        var host = (FileSystemHost)hostObject;
        host.SectorSize = 4096;
        host.SectorsPerAllocationUnit = 1;
        host.MaxComponentLength = 255;
        host.CaseSensitiveSearch = true;
        host.CasePreservedNames = true;
        host.UnicodeOnDisk = true;
        host.PersistentAcls = true;
        host.FileInfoTimeout = 1000;
        return Ok;
    }

    public override int GetVolumeInfo(out FspVolumeInfo info)
    {
        info = default;
        try
        {
            var drive = new DriveInfo(Path.GetPathRoot(AppStore.DirectoryPath)!);
            info.TotalSize = (ulong)drive.TotalSize;
            info.FreeSize = (ulong)drive.AvailableFreeSpace;
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "GetVolumeInfo"); }
    }

    public override int GetSecurityByName(string fileName, out uint attributes,
        ref byte[] securityDescriptor)
    {
        attributes = 0;
        try
        {
            var entry = Find(fileName);
            if (entry is null) return Missing;
            attributes = Describe(entry).FileAttributes;
            if (securityDescriptor is not null)
                securityDescriptor = (byte[])_acl.Clone();
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "GetSecurityByName"); }
    }

    public override int Open(string fileName, uint createOptions, uint grantedAccess,
        out object fileNode, out object fileDesc, out FspFileInfo info,
        out string normalizedName)
    {
        fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
        try
        {
            if (_newFiles.TryGetValue(Canonical(fileName), out var localStage))
            {
                var provisional = Find(fileName)!;
                localStage.RegisterHandle();
                var writable = new NewFile(provisional, localStage);
                fileNode = writable; fileDesc = writable;
                info = Describe(writable);
                Interlocked.Increment(ref _opened);
                return Ok;
            }
            var entry = Find(fileName);
            if (entry is null) return Missing;
            // No existing-file writes until Drive CAS semantics and
            // recovery journaling have passed two-client regression.
            // DELETE access (0x10000) is needed for a Windows file RENAME.
            // Deny content writes (data/append/EA), but allow a metadata
            // rename without making the existing binary content writable.
            if (!entry.Directory && (grantedAccess & 0x00000016u) != 0)
                return Denied;
            fileNode = entry; fileDesc = entry;
            info = Describe(entry);
            Interlocked.Increment(ref _opened);
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "Open"); }
    }

    public override void Close(object fileNode, object fileDesc)
    {
        if (fileDesc is NewFile staged)
        {
            try
            {
                if (Interlocked.Exchange(ref staged.Cleaned, 1) == 0 &&
                    staged.Stage.LastCleanup() &&
                    !staged.Stage.Uploaded && !staged.Stage.Discarded)
                    staged.Stage.Commit();
            }
            catch (Exception error) when (error is IOException or HttpRequestException)
            {
                Failure(error, "CloseNewFileRecoveryRetained");
            }
            finally
            {
                if (staged.Stage.LastClose())
                {
                    _newFiles.TryRemove(staged.Entry.Path, out _);
                    _recent.TryRemove(staged.Entry.Path, out _);
                    staged.Stage.Dispose();
                }
                Interlocked.Decrement(ref _opened);
            }
            return;
        }
        if (fileDesc is Entry)
            Interlocked.Decrement(ref _opened);
    }

    public override int GetFileInfo(object fileNode, object fileDesc,
        out FspFileInfo info)
    {
        info = fileDesc switch
        {
            NewFile staged => Describe(staged),
            Entry e => Describe(e),
            _ => default
        };
        return fileDesc is Entry or NewFile ? Ok : Denied;
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
        transferred = 0;
        if (fileDesc is NewFile staged)
        {
            if (offset >= (ulong)staged.Stage.Length) return Eof;
            try
            {
                byte[] chunk = new byte[(int)Math.Min(length,
                    Math.Min(4UL * 1024 * 1024, (ulong)staged.Stage.Length - offset))];
                int read = staged.Stage.Read((long)offset, chunk);
                if (read > 0) Marshal.Copy(chunk, 0, buffer, read);
                transferred = (uint)read;
                return Ok;
            }
            catch (IOException ex) { return Failure(ex, "ReadNewStage"); }
        }
        if (fileDesc is not Entry entry || entry.Directory) return Denied;
        if (entry.Mime.StartsWith("application/vnd.google-apps.",
            StringComparison.OrdinalIgnoreCase)) return Unsupported;
        if (offset >= (ulong)entry.Size) return Eof;
        try
        {
            var content = ReadCached(entry, (long)offset,
                (int)Math.Min((ulong)length, Math.Min((ulong)entry.Size - offset,
                    8UL * 1024 * 1024)));
            if (content.Length > 0) Marshal.Copy(content, 0, buffer, content.Length);
            transferred = (uint)content.Length;
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "Read"); }
    }

    public override bool ReadDirectoryEntry(object fileNode, object fileDesc,
        string pattern, string marker, ref object context, out string name,
        out FspFileInfo info)
    {
        name = null!; info = default;
        if (fileDesc is not Entry directory || !directory.Directory) return false;
        if (context is not Cursor cursor)
        {
            try
            {
                var entries = List(directory.Path).Where(item =>
                    string.IsNullOrEmpty(pattern) ||
                    FileSystemName.MatchesSimpleExpression(
                        pattern.Replace('<', '*').Replace('>', '?').Replace('"', '.'),
                        item.Name, ignoreCase: true)).ToList();
                if (directory.Path != "/")
                {
                    var parent = Find(Parent(directory.Path));
                    if (parent is not null) entries.Insert(0, parent with { Name = ".." });
                    entries.Insert(0, directory with { Name = "." });
                }
                cursor = new Cursor(entries.ToArray(), marker);
                context = cursor;
            }
            catch (Exception ex) when (ex is IOException or ArgumentException)
            {
                Failure(ex, "ReadDirectory");
                return false;
            }
        }
        if (cursor.Index >= cursor.Items.Length) return false;
        var next = cursor.Items[cursor.Index++];
        name = next.Name; info = Describe(next);
        return true;
    }

    public override int GetSecurity(object fileNode, object fileDesc,
        ref byte[] securityDescriptor)
    {
        securityDescriptor = (byte[])_acl.Clone();
        return Ok;
    }

    public override int Create(string fileName, uint createOptions,
        uint grantedAccess, uint fileAttributes, byte[] securityDescriptor,
        ulong allocationSize, out object fileNode, out object fileDesc,
        out FspFileInfo info, out string normalizedName)
    {
        fileNode = null!; fileDesc = null!; info = default; normalizedName = null!;
        if (!_allowFolderMutations)
            return Denied;
        try
        {
            string target = Canonical(fileName);
            if (Find(target) is not null) return Exists;
            var parent = Find(Parent(target));
            if (parent is null || !parent.Directory) return Missing;
            if ((createOptions & 1u) == 0)
            {
                // New binary files are always durable locally first.
                // Existing Drive IDs are never overwritten via this path.
                var stage = new DirectGoogleWriteStage(_api, _stageRoot,
                    parent.Id, target[(target.LastIndexOf('/') + 1)..]);
                if (!_newFiles.TryAdd(target, stage))
                {
                    stage.Discard();
                    return Exists;
                }
                var pendingEntry = Find(target)!;
                stage.RegisterHandle();
                var writer = new NewFile(pendingEntry, stage);
                fileNode = writer; fileDesc = writer; info = Describe(writer);
                Interlocked.Increment(ref _opened);
                return Ok;
            }
            var created = _api.CreateFolderAsync(parent.Id,
                target[(target.LastIndexOf('/') + 1)..]).GetAwaiter().GetResult();
            _recent.Clear(); _recentUntil.Clear();
            var entry = FromItem(parent.Path, created);
            _recent[entry.Path] = entry;
            fileNode = entry; fileDesc = entry; info = Describe(entry);
            Interlocked.Increment(ref _opened);
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "CreateFolder"); }
    }

    public override int CanDelete(object fileNode, object fileDesc, string fileName)
        => fileDesc is NewFile pending && !pending.Stage.Uploaded
            ? Ok : (_allowFolderMutations && fileDesc is Entry entry &&
                entry.Id != "root" ? Ok : Denied);

    public override int Write(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, bool writeToEndOfFile, bool constrainedIo,
        out uint transferred, out FspFileInfo info)
    {
        transferred = 0; info = default;
        if (fileDesc is not NewFile file || length > 4 * 1024 * 1024)
            return Denied;
        try
        {
            ulong position = writeToEndOfFile ? (ulong)file.Stage.Length : offset;
            if (constrainedIo && position >= (ulong)file.Stage.Length)
            {
                info = Describe(file);
                return Ok;
            }
            int permitted = (int)(constrainedIo ?
                Math.Min(length, (ulong)file.Stage.Length - position) : length);
            byte[] data = new byte[permitted];
            if (permitted > 0) Marshal.Copy(buffer, data, 0, permitted);
            file.Stage.Write(position, data, false);
            transferred = (uint)permitted;
            info = Describe(file);
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException or OverflowException)
        { return Failure(ex, "WriteNewFile"); }
    }

    public override int Flush(object fileNode, object fileDesc, out FspFileInfo info)
    {
        info = default;
        if (fileDesc is not NewFile file) return Ok;
        try
        {
            file.Stage.FlushLocal();
            // Flush durably saves local bytes but must not finalize a remote
            // upload: Office can flush repeatedly before its final write.
            info = Describe(file);
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or HttpRequestException)
        { return Failure(ex, "FlushNewFileRecoveryRetained"); }
    }

    public override int SetFileSize(object fileNode, object fileDesc, ulong newSize,
        bool setAllocationSize, out FspFileInfo info)
    {
        info = default;
        if (fileDesc is not NewFile file || newSize > long.MaxValue) return Denied;
        try
        {
            if (!setAllocationSize || (ulong)file.Stage.Length > newSize)
                file.Stage.SetLength((long)newSize);
            info = Describe(file);
            return Ok;
        }
        catch (IOException ex) { return Failure(ex, "SetFileSizeNewFile"); }
    }

    public override int Overwrite(object fileNode, object fileDesc,
        uint fileAttributes, bool replaceFileAttributes, ulong allocationSize,
        out FspFileInfo info)
    {
        info = default;
        if (fileDesc is not NewFile file) return Denied;
        try
        {
            file.Stage.SetLength(0);
            info = Describe(file);
            return Ok;
        }
        catch (IOException ex) { return Failure(ex, "OverwriteNewStage"); }
    }

    public override void Cleanup(object fileNode, object fileDesc,
        string fileName, uint flags)
    {
        if (fileDesc is Entry existing)
        {
            if ((flags & 1u) != 0 && _allowFolderMutations &&
                existing.Id != "root")
            {
                try
                {
                    var parent = Find(Parent(existing.Path));
                    if (parent is null) throw new DirectoryNotFoundException();
                    _api.TrashAsync(existing.Id, parent.Id,
                        existing.Version).GetAwaiter().GetResult();
                    _recent.Clear(); _recentUntil.Clear();
                }
                catch (Exception ex) when (ex is IOException or ArgumentException)
                { Failure(ex, "TrashReversible"); }
            }
            return;
        }
        if (fileDesc is not NewFile file) return;
        if (Interlocked.Exchange(ref file.Cleaned, 1) != 0) return;
        if ((flags & 1u) != 0)
        {
            try { file.Stage.Discard(); }
            catch (IOException ex) { Failure(ex, "DiscardNewFile"); }
            file.Stage.LastCleanup();
            return;
        }
        try
        {
            // Only the last Cleanup can commit the file. Other handles
            // may still be writing after their sibling handle closes.
            if (file.Stage.LastCleanup())
            {
                file.Stage.Commit();
                if (file.Stage.Uploaded)
                {
                    _newFiles.TryRemove(file.Entry.Path, out _);
                    _recent.TryRemove(file.Entry.Path, out _);
                    // WinFsp Close can be delayed by the Windows cache
                    // manager. The final Cleanup guarantees that no live
                    // handle can write; remove committed recovery bytes now.
                    file.Stage.Dispose();
                }
            }
        }
        catch (Exception ex) when (ex is IOException or HttpRequestException)
        { Failure(ex, "CleanupNewFileRecoveryRetained"); }
    }

    public override int Rename(object fileNode, object fileDesc,
        string fileName, string newFileName, bool replaceIfExists)
    {
        if (!_allowFolderMutations || fileDesc is not Entry entry ||
            entry.Id == "root") return Denied;
        try
        {
            string target = Canonical(newFileName);
            if (Find(target) is not null) return Exists;
            var sourceParent = Find(Parent(entry.Path));
            var destinationParent = Find(Parent(target));
            if (sourceParent is null || destinationParent is null ||
                !destinationParent.Directory) return Missing;
            string newName = target[(target.LastIndexOf('/') + 1)..];
            if (sourceParent.Id == destinationParent.Id)
                _api.RenameAsync(entry.Id, newName,
                    sourceParent.Id).GetAwaiter().GetResult();
            else
                _api.MoveAsync(entry.Id, sourceParent.Id,
                    destinationParent.Id, newName).GetAwaiter().GetResult();
            _recent.Clear(); _recentUntil.Clear();
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "RenameFolder"); }
    }

    public void Dispose()
    {
        _readCache.Clear();
        foreach (var stage in _newFiles.Values) stage.Dispose();
        _newFiles.Clear();
        _recent.Clear(); _recentUntil.Clear();
    }
}

internal static class DirectGoogleWinFspHost
{
    internal static int Run(Guid profileId, string letter, bool writeFolders)
    {
        if (letter.Length != 2 || letter[1] != ':' ||
            letter[0] is < 'D' or > 'Z' ||
            Environment.GetLogicalDrives().Any(d =>
                d.StartsWith(letter + "\\", StringComparison.OrdinalIgnoreCase)))
            throw new ArgumentException("Direct Google pilot requires a free drive letter");
        var profile = AppStore.Load().Profiles.SingleOrDefault(p => p.Id == profileId);
        if (profile is null || profile.Protocol != "GoogleDrive")
            throw new ArgumentException("Saved Google profile required");
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
        var token = GoogleDirectOAuthSession.ImportCustomProfile(http, profile);
        var api = new GoogleDirectApiClient(http, token.AccessTokenAsync,
            cacheTtl: TimeSpan.FromSeconds(30), maxRetries: 2);
        // The direct pilot must prove the root is usable before occupying
        // a drive letter. Avoid reporting a mounted-but-unusable drive.
        _ = api.ListChildrenAsync("root").GetAwaiter().GetResult();
        string recovery = Path.Combine(AppStore.DirectoryPath,
            "google-direct-stages", profile.Id.ToString("N"));
        using var provider = new DirectGoogleWinFsp(api, Path.GetTempPath(),
            writeFolders, recovery);
        using var host = new FileSystemHost(provider);
        host.FileSystemName = "NTFS";
        // Pilot is local fixed volume; no synthetic NASMount UNC name.
        int status = host.Mount(letter, null, true);
        if (status < 0)
            throw new IOException("Direct Google WinFsp mount failed 0x" +
                status.ToString("X8"));
        Console.WriteLine("DIRECT_GOOGLE_WINFSP_PILOT_READY rclone=false letter=" +
            letter + " folders=" + (writeFolders ? "write" : "read"));
        Console.Out.Flush();
        // An isolated pilot pipe avoids terminating a normal Google mount.
        string pipeName = "NASMountDirectGooglePilot_" + profileId.ToString("N");
        while (true)
        {
            using var pipe = new NamedPipeServerStream(pipeName,
                PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                PipeOptions.None);
            pipe.WaitForConnection();
            using var reader = new StreamReader(pipe, leaveOpen: true);
            using var writer = new StreamWriter(pipe, leaveOpen: true)
            { AutoFlush = true };
            string request = reader.ReadLine() ?? "";
            if (request == "PING")
                writer.WriteLine("READY " + provider.OpenedHandles +
                    " rclone=false pending=" + provider.PendingRecoveryCount);
            else if (request == "STATUS")
                writer.WriteLine("READY " + provider.OpenedHandles +
                    " PENDING " + provider.PendingRecoveryCount);
            else if (request == "QUIT" && provider.OpenedHandles == 0 &&
                provider.PendingRecoveryCount == 0)
            { writer.WriteLine("OK"); break; }
            else writer.WriteLine("BUSY " + provider.OpenedHandles +
                " PENDING " + provider.PendingRecoveryCount);
        }
        return 0;
    }
}
