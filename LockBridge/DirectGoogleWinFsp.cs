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

    internal DirectGoogleWinFsp(GoogleDirectApiClient api,
        string localAclDirectory, bool allowFolderMutations)
    {
        _api = api;
        _allowFolderMutations = allowFolderMutations;
        _acl = new DirectoryInfo(localAclDirectory).GetAccessControl()
            .GetSecurityDescriptorBinaryForm();
        _stageRoot = Path.Combine(AppStore.DirectoryPath, "google-direct-stages");
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
        if (_recent.TryGetValue(path, out var cached)) return cached;
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
        foreach (var item in items) _recent[item.Path] = item;
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
            var entry = Find(fileName);
            if (entry is null) return Missing;
            // No existing-file writes until Drive CAS semantics and
            // recovery journaling have passed two-client regression.
            if (!entry.Directory && (grantedAccess & 0x00010116u) != 0)
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
        if (fileDesc is Entry)
            Interlocked.Decrement(ref _opened);
    }

    public override int GetFileInfo(object fileNode, object fileDesc,
        out FspFileInfo info)
    {
        info = fileDesc is Entry e ? Describe(e) : default;
        return fileDesc is Entry ? Ok : Denied;
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
        transferred = 0;
        if (fileDesc is not Entry entry || entry.Directory) return Denied;
        if (entry.Mime.StartsWith("application/vnd.google-apps.",
            StringComparison.OrdinalIgnoreCase)) return Unsupported;
        if (offset >= (ulong)entry.Size) return Eof;
        try
        {
            var content = _api.ReadRangeAsync(entry.Id, (long)offset,
                (int)Math.Min((ulong)length, Math.Min((ulong)entry.Size - offset,
                    8UL * 1024 * 1024))).GetAwaiter().GetResult();
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
        if (!_allowFolderMutations || (createOptions & 1u) == 0)
            return Denied;
        try
        {
            string target = Canonical(fileName);
            if (Find(target) is not null) return Exists;
            var parent = Find(Parent(target));
            if (parent is null || !parent.Directory) return Missing;
            var created = _api.CreateFolderAsync(parent.Id,
                target[(target.LastIndexOf('/') + 1)..]).GetAwaiter().GetResult();
            _recent.Clear();
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
        => Denied; // Trash/version-aware delete not yet validated.

    public override int Rename(object fileNode, object fileDesc,
        string fileName, string newFileName, bool replaceIfExists)
    {
        if (!_allowFolderMutations || fileDesc is not Entry entry ||
            !entry.Directory || replaceIfExists) return Denied;
        try
        {
            string target = Canonical(newFileName);
            if (Parent(target) != Parent(entry.Path)) return Unsupported;
            if (Find(target) is not null) return Exists;
            var parent = Find(Parent(entry.Path));
            if (parent is null) return Missing;
            _api.RenameAsync(entry.Id, target[(target.LastIndexOf('/') + 1)..],
                parent.Id).GetAwaiter().GetResult();
            _recent.Clear();
            return Ok;
        }
        catch (Exception ex) when (ex is IOException or ArgumentException)
        { return Failure(ex, "RenameFolder"); }
    }

    public void Dispose()
    {
        _recent.Clear();
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
        using var provider = new DirectGoogleWinFsp(api, Path.GetTempPath(),
            writeFolders);
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
                    " rclone=false");
            else if (request == "QUIT" && provider.OpenedHandles == 0)
            { writer.WriteLine("OK"); break; }
            else writer.WriteLine("BUSY " + provider.OpenedHandles);
        }
        return 0;
    }
}
