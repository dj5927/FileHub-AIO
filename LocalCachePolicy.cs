using System.Diagnostics;
using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

// Only local, dedicated cache directories. Never route recovery journals,
// credentials, user files or the installation itself through this policy.
internal static class LocalCachePolicy
{
    internal const int DefaultMinutes = 30;
    internal const int DefaultGiB = 5;
    internal static int Minutes(AppConfig config) =>
        config.CacheRetentionMinutes is >= 5 and <= 1440
            ? config.CacheRetentionMinutes : DefaultMinutes;
    internal static int GiB(AppConfig config) =>
        config.CacheMaxGiB is >= 1 and <= 200
            ? config.CacheMaxGiB : DefaultGiB;
    internal static AppConfig Current() => File.Exists(AppStore.ConfigPath)
        ? AppStore.ReadConfig(AppStore.ConfigPath) : new AppConfig();
    internal static string DefaultRoot => Path.Combine(AppStore.DirectoryPath, "cache");

    // Reject directory junctions, symlinks, network and removable roots.
    // Checking ancestors also prevents following a link outside our root.
    internal static bool PlainPath(string path)
    {
        try
        {
            for (string? p = Path.GetFullPath(path); p is not null;
                p = Path.GetDirectoryName(p))
                if (Path.Exists(p) &&
                    (File.GetAttributes(p) & FileAttributes.ReparsePoint) != 0)
                    return false;
            return true;
        }
        catch { return false; }
    }

    internal static string Root(AppConfig config)
    {
        if (string.IsNullOrWhiteSpace(config.CacheParentDirectory))
        {
            if (!PlainPath(DefaultRoot)) throw new IOException("Cache root is linked");
            return DefaultRoot;
        }
        if (!Path.IsPathFullyQualified(config.CacheParentDirectory))
            throw new IOException("Cache directory must be an absolute local path");
        string parent = Path.GetFullPath(config.CacheParentDirectory);
        if (!Directory.Exists(parent) || new DriveInfo(Path.GetPathRoot(parent)!).DriveType != DriveType.Fixed ||
            !PlainPath(parent))
            throw new IOException("Cache directory is unavailable or not a fixed local disk");
        string path = Path.Combine(parent, "FileHubAIO-cache");
        if (!PlainPath(path)) throw new IOException("Cache root is linked");
        // Avoid placing a disposable cache inside persistent app data.
        string app = Path.GetFullPath(AppStore.DirectoryPath).TrimEnd('\\') + "\\";
        if ((path.TrimEnd('\\') + "\\").StartsWith(app, StringComparison.OrdinalIgnoreCase))
            throw new IOException("Choose a folder outside FileHub application data");
        return path;
    }

    internal static string ProfileRoot(Guid id, AppConfig config) =>
        Path.Combine(Root(config), id.ToString("N"));

    internal static void AppendArgs(ICollection<string> args, AppConfig config)
    {
        args.Add("--vfs-cache-max-age");
        args.Add(Minutes(config).ToString(CultureInfo.InvariantCulture) + "m");
        args.Add("--vfs-cache-max-size");
        args.Add(GiB(config).ToString(CultureInfo.InvariantCulture) + "Gi");
        args.Add("--vfs-cache-poll-interval");
        args.Add("30s");
        // The cap is a soft target. rclone never evicts active/dirty items.
    }

    internal static bool NoBackendProcesses()
    {
        try
        {
            foreach (string name in new[] { "rclone", "rclone-terabox",
                "rclone-filehub-google", "NASMountLockBridge" })
            {
                var processes = Process.GetProcessesByName(name);
                try { if (processes.Length != 0) return false; }
                finally { foreach (var process in processes) process.Dispose(); }
            }
            return true;
        }
        catch { return false; }
    }

    internal static bool HasOtherGui()
    {
        try
        {
            var processes = Process.GetProcessesByName("FileHubAIO");
            try { return processes.Any(p => p.Id != Environment.ProcessId); }
            finally { foreach (var p in processes) p.Dispose(); }
        }
        catch { return true; }
    }

    internal static bool PlainTree(string directory)
    {
        try
        {
            if (!PlainPath(directory)) return false;
            if (!Directory.Exists(directory)) return !Path.Exists(directory);
            foreach (string item in Directory.EnumerateFileSystemEntries(directory))
            {
                var attr = File.GetAttributes(item);
                if ((attr & FileAttributes.ReparsePoint) != 0) return false;
                if ((attr & FileAttributes.Directory) != 0 && !PlainTree(item)) return false;
            }
            return true;
        }
        catch { return false; }
    }

    internal static bool DeleteTree(string directory)
    {
        try
        {
            if (!PlainTree(directory)) return false;
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
            return !Path.Exists(directory);
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
    }

    internal static IEnumerable<string> Files(string root)
    {
        if (!Directory.Exists(root) || !PlainPath(root)) yield break;
        var pending = new Stack<string>();
        pending.Push(root);
        while (pending.Count != 0)
        {
            string folder = pending.Pop();
            string[] entries;
            try { entries = Directory.GetFileSystemEntries(folder); }
            catch { continue; }
            foreach (string entry in entries)
            {
                FileAttributes attrs;
                try { attrs = File.GetAttributes(entry); } catch { continue; }
                if ((attrs & FileAttributes.ReparsePoint) != 0) continue;
                if ((attrs & FileAttributes.Directory) != 0) pending.Push(entry);
                else yield return entry;
            }
        }
    }

    internal static long Bytes(string path)
    {
        long result = 0;
        foreach (string file in Files(path))
            try { result += new FileInfo(file).Length; } catch { }
        return result;
    }

    // rclone persists an explicit Dirty flag, Size, ATime and Fingerprint
    // in vfsMeta. Unknown schema, orphan data, or incomplete/dirty state
    // must remain untouched, including entries of deleted profiles.
    internal static long CleanRclone(string root, DateTimeOffset cutoff, bool all)
    {
        if (!Directory.Exists(root) || !PlainPath(root)) return 0;
        long removed = 0;
        foreach (string profile in Directory.GetDirectories(root))
        {
            if (!Guid.TryParseExact(Path.GetFileName(profile), "N", out _) ||
                !PlainPath(profile)) continue;
            string meta = Path.Combine(profile, "vfsMeta");
            string data = Path.Combine(profile, "vfs");
            foreach (string manifest in Files(meta))
            {
                try
                {
                    string target = Path.Combine(data, Path.GetRelativePath(meta, manifest));
                    if (!PlainPath(target) || !File.Exists(target) ||
                        new FileInfo(manifest).Length > 4 * 1024 * 1024) continue;
                    using var doc = JsonDocument.Parse(File.ReadAllText(manifest));
                    var entry = doc.RootElement;
                    if (!entry.TryGetProperty("Dirty", out var dirty) ||
                        dirty.ValueKind != JsonValueKind.False ||
                        !entry.TryGetProperty("Size", out var size) ||
                        !size.TryGetInt64(out long length) || length < 0 ||
                        new FileInfo(target).Length != length ||
                        !entry.TryGetProperty("Fingerprint", out var fingerprint) ||
                        fingerprint.ValueKind != JsonValueKind.String ||
                        string.IsNullOrWhiteSpace(fingerprint.GetString()) ||
                        !entry.TryGetProperty("ATime", out var access) ||
                        !access.TryGetDateTimeOffset(out var accessed) ||
                        (!all && (accessed > cutoff ||
                            File.GetLastWriteTimeUtc(manifest) > cutoff.UtcDateTime)))
                        continue;
                    // Keep metadata until data deletion succeeds; never
                    // delete data whose clean metadata cannot be verified.
                    using (new FileStream(target, FileMode.Open, FileAccess.Read,
                        FileShare.None)) { }
                    File.Delete(target);
                    File.Delete(manifest);
                    removed += length;
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                catch (JsonException) { }
                catch (InvalidOperationException) { }
            }
        }
        return removed;
    }

    // Native WebDAV executable/image cache contains only downloaded read
    // data, never uploads. Session directories may survive a forced exit.
    internal static void CleanReadSessions(string cacheRoot, DateTime cutoff, bool all)
    {
        void Clean(string root, string prefix)
        {
            if (!Directory.Exists(root) || !PlainPath(root)) return;
            foreach (string dir in Directory.GetDirectories(root))
            {
                string name = Path.GetFileName(dir);
                if (!name.StartsWith(prefix, StringComparison.Ordinal) ||
                    !Guid.TryParseExact(name[prefix.Length..], "N", out _)) continue;
                if (all || Directory.GetLastWriteTimeUtc(dir) <= cutoff)
                    DeleteTree(dir);
            }
        }
        Clean(Path.Combine(cacheRoot, "webdav-read"), "");
        Clean(Path.GetTempPath(), "FileHubDirectWebDavLab_");
    }

    internal static void CleanOffline(AppConfig config, bool all)
    {
        if (!NoBackendProcesses() || HasOtherGui()) return;
        string root = Root(config);
        var cutoff = DateTimeOffset.UtcNow.AddMinutes(-Minutes(config));
        CleanRclone(root, cutoff, all);
        CleanReadSessions(root, cutoff.UtcDateTime, all);
        // Old default cache may contain orphan profiles. No write recovery
        // folder is ever visited by this cleanup.
        if (!root.Equals(DefaultRoot, StringComparison.OrdinalIgnoreCase))
        {
            CleanRclone(DefaultRoot, cutoff, all);
            CleanReadSessions(DefaultRoot, cutoff.UtcDateTime, all);
        }
    }

    internal static void PolicyTest()
    {
        string root = Path.Combine(Path.GetTempPath(), "FileHubCacheQA_" + Guid.NewGuid().ToString("N"));
        string profile = Path.Combine(root, Guid.NewGuid().ToString("N"));
        string data = Path.Combine(profile, "vfs", "remote");
        string meta = Path.Combine(profile, "vfsMeta", "remote");
        Directory.CreateDirectory(data); Directory.CreateDirectory(meta);
        try
        {
            foreach (string name in new[] { "clean", "dirty", "unknown", "fresh", "orphan" })
            {
                File.WriteAllText(Path.Combine(data, name), "DATA");
                if (name == "orphan") continue;
                string file = Path.Combine(meta, name);
                File.WriteAllText(file, JsonSerializer.Serialize(new {
                    Dirty = name == "dirty", Size = 4,
                    Fingerprint = name == "unknown" ? "" : "remote-revision",
                    ATime = DateTimeOffset.UtcNow.AddMinutes(name == "fresh" ? 0 : -90)
                }));
                if (name != "fresh") File.SetLastWriteTimeUtc(file, DateTime.UtcNow.AddHours(-2));
            }
            var cutoff = DateTimeOffset.UtcNow.AddMinutes(-30);
            if (CleanRclone(root, cutoff, false) != 4 ||
                File.Exists(Path.Combine(data, "clean")) ||
                new[] { "dirty", "unknown", "fresh", "orphan" }.Any(n => !File.Exists(Path.Combine(data, n))))
                throw new IOException("Cache cleanup removed unverified or fresh data");
            if (CleanRclone(root, cutoff, true) != 4 ||
                File.Exists(Path.Combine(data, "fresh")) ||
                new[] { "dirty", "unknown", "orphan" }.Any(n => !File.Exists(Path.Combine(data, n))))
                throw new IOException("Manual cache cleanup did not preserve recovery");
            var args = new List<string>(); AppendArgs(args, new AppConfig());
            if (!args.SequenceEqual(new[] { "--vfs-cache-max-age", "30m",
                "--vfs-cache-max-size", "5Gi", "--vfs-cache-poll-interval", "30s" }))
                throw new IOException("Cache defaults incorrect");
            string logs = Path.Combine(root, "logs");
            Directory.CreateDirectory(logs);
            string oldLog = Path.Combine(logs, "20261125-0100-1.log");
            string recentLog = Path.Combine(logs, "20261125-0130-1.log");
            string legacyLog = Path.Combine(logs, "2026-11-24.log");
            string unrelatedLog = Path.Combine(logs, "user.log");
            foreach (string file in new[] { oldLog, recentLog, legacyLog, unrelatedLog })
                File.WriteAllText(file, "LOG");
            DateTime now = DateTime.UtcNow;
            File.SetLastWriteTimeUtc(oldLog, now.AddMinutes(-31));
            File.SetLastWriteTimeUtc(recentLog, now.AddMinutes(-5));
            AppStore.PruneLogDirectory(logs, now);
            if (File.Exists(oldLog) || File.Exists(legacyLog) ||
                !File.Exists(recentLog) || !File.Exists(unrelatedLog))
                throw new IOException("Rolling log retention mismatch");
            Console.WriteLine("CACHE_30_MINUTE_CAP_AND_DIRTY_RECOVERY_PRESERVED_PASS");
            Console.WriteLine("LOG_30_MINUTE_RETENTION_LEGACY_CLEANUP_PASS");
        }
        finally { Directory.Delete(root, true); }
    }
}
