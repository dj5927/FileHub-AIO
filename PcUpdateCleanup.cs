using System.Text.RegularExpressions;

namespace NASMount;

internal static class PcUpdateCleanup
{
    private static readonly Regex Tag = new(@"^pc-v(\d+\.\d+\.\d+)$",
        RegexOptions.CultureInvariant);
    private static readonly Regex Backup = new(@"^pc-v(\d+\.\d+\.\d+)-\d{14}$",
        RegexOptions.CultureInvariant);

    private static bool InstalledOrOlder(string name, Regex pattern, Version installed)
    {
        var m = pattern.Match(name);
        if (!m.Success || !Version.TryParse(m.Groups[1].Value, out var version)) return false;
        return version <= new Version(installed.Major, installed.Minor, installed.Build);
    }

    internal static bool CleanInstalled(string root, Version installed, string runningExe)
    {
        if (!Directory.Exists(root)) return true;
        string path = Path.GetFullPath(root).TrimEnd('\\', '/') + Path.DirectorySeparatorChar;
        // Never delete the staged executable from which this instance runs.
        if (Path.GetFullPath(runningExe).StartsWith(path, StringComparison.OrdinalIgnoreCase) ||
            !LocalCachePolicy.PlainPath(root)) return false;
        bool complete = true;
        try
        {
            foreach (string dir in Directory.GetDirectories(root))
                if (InstalledOrOlder(Path.GetFileName(dir), Tag, installed))
                    complete &= LocalCachePolicy.DeleteTree(dir);
            string backup = Path.Combine(root, "backups");
            if (Directory.Exists(backup))
            {
                if (!LocalCachePolicy.PlainPath(backup)) return false;
                foreach (string dir in Directory.GetDirectories(backup))
                    if (InstalledOrOlder(Path.GetFileName(dir), Backup, installed))
                        complete &= LocalCachePolicy.DeleteTree(dir);
                if (!Directory.EnumerateFileSystemEntries(backup).Any())
                    Directory.Delete(backup);
            }
            if (!Directory.EnumerateFileSystemEntries(root).Any()) Directory.Delete(root);
        }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return false; }
        return complete;
    }

    internal static bool RunAfterNormalStartup()
    {
        // The helper runs from the staged package and may still be exiting.
        // Retry later without touching any active update or future release.
        if (LocalCachePolicy.HasOtherGui()) return false;
        return CleanInstalled(PcUpdateService.UpdateRoot,
            PcUpdateService.InstalledVersion, Environment.ProcessPath ?? "");
    }

    internal static void PolicyTest()
    {
        string root = Path.Combine(Path.GetTempPath(), "FileHubUpdateCleanQA_" + Guid.NewGuid().ToString("N"));
        string updates = Path.Combine(root, "updates");
        string live = Path.Combine(root, "live", "FileHubAIO.exe");
        try
        {
            foreach (string name in new[] { "pc-v1.3.143", "pc-v1.3.145", "pc-v1.3.146",
                "pc-v1.3.147", "backups/pc-v1.3.144-20261124000000",
                "backups/pc-v1.3.146-20261125000000",
                "backups/pc-v1.3.147-20261125000000", "unrelated" })
            {
                string dir = Path.Combine(updates, name);
                Directory.CreateDirectory(dir);
                File.WriteAllText(Path.Combine(dir, "file"), "data");
            }
            Directory.CreateDirectory(Path.GetDirectoryName(live)!);
            File.WriteAllText(live, "KEEP");
            string staging = Path.Combine(updates, "pc-v1.3.146", "package", "FileHubAIO.exe");
            var version = new Version(1, 3, 146, 0);
            if (CleanInstalled(updates, version, staging) ||
                !Directory.Exists(Path.Combine(updates, "pc-v1.3.143")))
                throw new IOException("Staged updater must not remove its package");
            if (!CleanInstalled(updates, new Version(1, 3, 145, 0), live) ||
                !Directory.Exists(Path.Combine(updates, "pc-v1.3.146")) ||
                !Directory.Exists(Path.Combine(updates, "backups/pc-v1.3.146-20261125000000")))
                throw new IOException("Failed/uninstalled update recovery was removed");
            if (!CleanInstalled(updates, version, live) ||
                Directory.Exists(Path.Combine(updates, "pc-v1.3.146")) ||
                Directory.Exists(Path.Combine(updates, "backups/pc-v1.3.146-20261125000000")) ||
                Directory.Exists(Path.Combine(updates, "backups/pc-v1.3.144-20261124000000")) ||
                !Directory.Exists(Path.Combine(updates, "pc-v1.3.147")) ||
                !Directory.Exists(Path.Combine(updates, "unrelated")) ||
                File.ReadAllText(live) != "KEEP")
                throw new IOException("Successful update cleanup incorrect");
            Console.WriteLine("OTA_SUCCESS_PURGES_CURRENT_AND_OLD_PACKAGES_AND_BACKUPS_PASS");
        }
        finally { Directory.Delete(root, true); }
    }
}
