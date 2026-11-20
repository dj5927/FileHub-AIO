using System.IO.Enumeration;
using System.Runtime.InteropServices;
using System.Collections.Concurrent;
using Fsp;
using Fsp.Interop;
using VolumeInfo = Fsp.Interop.VolumeInfo;
using FileInfo = Fsp.Interop.FileInfo;

namespace NASMount.LockBridge;

/// <summary>
/// Experimental direct WinFsp-to-HTTP read-only volume. This is NOT the
/// production mount: Office writes, rename, multi-PC locks and provider
/// synchronization must be implemented before selecting it for real profiles.
/// </summary>
internal class DirectWebDavReadOnlyFileSystem : FileSystemBase, IDisposable
{
    private const int Success = 0;
    private const int NotFound = unchecked((int)0xC0000034);
    private const int AccessDenied = unchecked((int)0xC0000022);
    private const int EndOfFile = unchecked((int)0xC0000011);
    protected readonly DirectWebDavReadOnlyBackend _backend;
    protected readonly byte[] _security;
    private readonly ConcurrentDictionary<string, DirectWebDavReadOnlyBackend.Entry[]>
        _watchedFolders = new(StringComparer.OrdinalIgnoreCase);
    private FileSystemHost? _host;
    private System.Threading.Timer? _watchTimer;
    private int _polling;

    private sealed class DirectoryCursor
    {
        internal readonly DirectWebDavReadOnlyBackend.Entry[] Items;
        internal int Index;
        internal DirectoryCursor(DirectWebDavReadOnlyBackend.Entry[] items, string? marker)
        {
            Items = items;
            if (marker is not null)
            {
                int at = Array.FindIndex(items, e => string.Equals(e.Name, marker,
                    StringComparison.OrdinalIgnoreCase));
                Index = at < 0 ? 0 : at + 1;
            }
        }
    }

    internal DirectWebDavReadOnlyFileSystem(DirectWebDavReadOnlyBackend backend,
        string aclSourceDirectory)
    {
        _backend = backend;
        _security = new DirectoryInfo(aclSourceDirectory).GetAccessControl()
            .GetSecurityDescriptorBinaryForm();
    }

    internal void StartWatching(FileSystemHost host)
    {
        _host = host;
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_WATCHER_STARTED");
        _watchTimer = new System.Threading.Timer(_ => PollWatchedFolders(), null,
            TimeSpan.FromSeconds(2), TimeSpan.FromSeconds(2));
    }

    private void Watch(string directory)
    {
        _watchedFolders.TryAdd(directory, _backend.List(directory));
        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
            Console.Error.WriteLine("DIRECT_WATCHED " + directory);
    }

    private void PollWatchedFolders()
    {
        if (Interlocked.Exchange(ref _polling, 1) != 0) return;
        try
        {
            foreach (var pair in _watchedFolders.ToArray())
            {
                DirectWebDavReadOnlyBackend.Entry[] fresh;
                try { fresh = _backend.RefreshList(pair.Key); }
                catch (Exception ex)
                {
                    if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                        Console.Error.WriteLine("DIRECT_WATCH_QUERY_ERROR " + pair.Key + " " + ex.GetType().Name);
                    continue;
                }
                if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                    Console.Error.WriteLine("DIRECT_WATCH_POLL " + pair.Key + " prior=" + pair.Value.Length + " current=" + fresh.Length);
                var prior = pair.Value.ToDictionary(e => e.Name,
                    StringComparer.OrdinalIgnoreCase);
                var current = fresh.ToDictionary(e => e.Name,
                    StringComparer.OrdinalIgnoreCase);
                var updates = new List<NotifyInfo>();
                string prefix = pair.Key == "/" ? "\\" :
                    pair.Key.Replace('/', '\\').TrimEnd('\\') + "\\";
                foreach (var old in prior.Values)
                {
                    bool found = current.TryGetValue(old.Name, out var now);
                    if (!found || now!.ETag != old.ETag ||
                        now.Size != old.Size || now.ModifiedUtc != old.ModifiedUtc)
                    {
                        _backend.Invalidate(old.Path);
                        updates.Add(new NotifyInfo {
                            FileName = prefix + old.Name,
                            Action = found ? NotifyAction.Modified : NotifyAction.Removed,
                            Filter = found ? NotifyFilter.ChangeLastWrite :
                                old.IsDirectory ? NotifyFilter.ChangeDirName :
                                NotifyFilter.ChangeFileName
                        });
                    }
                }
                foreach (var added in current.Values)
                    if (!prior.ContainsKey(added.Name))
                    {
                        _backend.Invalidate(added.Path);
                        updates.Add(new NotifyInfo {
                            FileName = prefix + added.Name,
                            Action = NotifyAction.Added,
                            Filter = added.IsDirectory ? NotifyFilter.ChangeDirName :
                                NotifyFilter.ChangeFileName
                        });
                    }
                _watchedFolders[pair.Key] = fresh;
                if (updates.Count is > 0 and <= 160 && _host is { } host &&
                    host.NotifyBegin(0) >= 0)
                {
                    try
                    {
                        int n = host.Notify(updates.ToArray());
                        if (Environment.GetEnvironmentVariable("FILEHUB_DIRECT_DEBUG") == "1")
                            Console.Error.WriteLine("DIRECT_WATCH_NOTIFY result=" + n + " count=" + updates.Count);
                    }
                    finally { host.NotifyEnd(); }
                }
            }
        }
        finally { Volatile.Write(ref _polling, 0); }
    }

    public void Dispose()
    {
        _watchTimer?.Dispose();
        _host = null;
        _watchedFolders.Clear();
    }

    public override int Init(object host0)
    {
        var host = (FileSystemHost)host0;
        host.SectorSize = 4096;
        host.SectorsPerAllocationUnit = 1;
        host.MaxComponentLength = 255;
        host.CaseSensitiveSearch = true;
        host.CasePreservedNames = true;
        host.UnicodeOnDisk = true;
        host.PersistentAcls = true;
        host.FileInfoTimeout = 1000;
        return Success;
    }

    public override int GetVolumeInfo(out VolumeInfo info)
    {
        // A lab must not invent a NAS quota. Unknown (0/0) is intentional.
        info = default;
        return Success;
    }

    protected static FileInfo Describe(DirectWebDavReadOnlyBackend.Entry entry)
    {
        FileInfo info = default;
        info.FileAttributes = (uint)(entry.IsDirectory
            ? System.IO.FileAttributes.Directory
            : System.IO.FileAttributes.ReadOnly);
        info.FileSize = entry.IsDirectory ? 0 : (ulong)entry.Size;
        info.AllocationSize = (info.FileSize + 4095) / 4096 * 4096;
        ulong when = (ulong)entry.ModifiedUtc.ToFileTimeUtc();
        info.CreationTime = when;
        info.LastAccessTime = when;
        info.LastWriteTime = when;
        info.ChangeTime = when;
        return info;
    }

    protected virtual FileInfo DescribeEntry(
        DirectWebDavReadOnlyBackend.Entry entry) => Describe(entry);

    public override int GetSecurityByName(string fileName, out uint attributes,
        ref byte[] securityDescriptor)
    {
        var stat = _backend.Stat(fileName);
        if (stat is null) { attributes = 0; return NotFound; }
        attributes = DescribeEntry(stat).FileAttributes;
        if (securityDescriptor is not null)
            securityDescriptor = (byte[])_security.Clone();
        return Success;
    }

    public override int Open(string fileName, uint createOptions, uint grantedAccess,
        out object fileNode, out object fileDesc, out FileInfo info,
        out string normalizedName)
    {
        fileNode = null!;
        fileDesc = null!;
        info = default;
        normalizedName = null!;
        // Read-only test. Never quietly accept an upload or Office save.
        if ((grantedAccess & 0x00000116) != 0) return AccessDenied;
        var stat = _backend.Stat(fileName);
        if (stat is null) return NotFound;
        fileNode = stat;
        fileDesc = stat;
        info = DescribeEntry(stat);
        return Success;
    }

    public override int GetFileInfo(object fileNode, object fileDesc, out FileInfo info)
    {
        info = DescribeEntry((DirectWebDavReadOnlyBackend.Entry)fileDesc);
        return Success;
    }

    public override int Read(object fileNode, object fileDesc, IntPtr buffer,
        ulong offset, uint length, out uint transferred)
    {
        transferred = 0;
        var stat = (DirectWebDavReadOnlyBackend.Entry)fileDesc;
        if (stat.IsDirectory) return AccessDenied;
        if (offset >= (ulong)stat.Size) return EndOfFile;
        var data = _backend.Read(stat, offset, (int)Math.Min(length, 4U * 1024 * 1024));
        if (data.Length > 0) Marshal.Copy(data, 0, buffer, data.Length);
        transferred = (uint)data.Length;
        return Success;
    }

    public override bool ReadDirectoryEntry(object fileNode, object fileDesc,
        string pattern, string marker, ref object context, out string name,
        out FileInfo info)
    {
        var directory = (DirectWebDavReadOnlyBackend.Entry)fileDesc;
        if (context is not DirectoryCursor cursor)
        {
            Watch(directory.Path);
            var entries = ListForDirectory(directory.Path).Where(entry =>
                string.IsNullOrEmpty(pattern) ||
                FileSystemName.MatchesSimpleExpression(
                    pattern.Replace('<', '*').Replace('>', '?').Replace('"', '.'),
                    entry.Name, ignoreCase: true)).ToList();
            // WinFsp expects dot entries on nested directories.
            if (directory.Path != "/")
            {
                var parent = _backend.Stat(Path.GetDirectoryName(
                    directory.Path.Replace('/', '\\')) ?? "/");
                if (parent is not null) entries.Insert(0,
                    parent with { Name = ".." });
                entries.Insert(0, directory with { Name = "." });
            }
            cursor = new DirectoryCursor(entries.ToArray(), marker);
            context = cursor;
        }
        if (cursor.Index >= cursor.Items.Length)
        {
            name = null!;
            info = default;
            return false;
        }
        var item = cursor.Items[cursor.Index++];
        name = item.Name;
        info = DescribeEntry(item);
        return true;
    }

    protected virtual DirectWebDavReadOnlyBackend.Entry[] ListForDirectory(
        string directory) => _backend.List(directory);

    public override int GetSecurity(object fileNode, object fileDesc,
        ref byte[] securityDescriptor)
    {
        securityDescriptor = (byte[])_security.Clone();
        return Success;
    }

    internal static void PolicyTest()
    {
        var test = new DirectWebDavReadOnlyBackend.Entry("/a.exe", "a.exe",
            false, 4000, DateTime.UtcNow);
        var fileInfo = Describe(test);
        if (fileInfo.FileSize != 4000 ||
            (fileInfo.FileAttributes & (uint)System.IO.FileAttributes.ReadOnly) == 0)
            throw new InvalidOperationException("Direct WinFsp file metadata mismatch");
        var dir = Describe(test with { IsDirectory = true });
        if (dir.FileSize != 0 ||
            (dir.FileAttributes & (uint)System.IO.FileAttributes.Directory) == 0)
            throw new InvalidOperationException("Direct WinFsp directory metadata mismatch");
        Console.WriteLine("DIRECT_WEBDAV_ONE_WINFSP_READONLY_METADATA_POLICY_PASS");
    }
}
