using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;

namespace NASMount;

// Launched from the NEW staged exe after the OLD instance safely dismounts
// every FileHub-managed drive. No scripts, administrator privileges, or
// third-party updater binaries are downloaded or executed.
internal static class PcUpdateApply
{
    internal sealed record Plan(
        int OldPid, string InstallDirectory, string StageDirectory,
        string Version, DateTime CreatedUtc);

    internal static int RunHelper(string planFile)
    {
        try
        {
            Plan plan = ReadAndValidate(planFile);
            if (!WaitForOldInstance(plan.OldPid))
                throw new IOException("Old process stayed active; refused to replace executable");
            string backup = Path.Combine(PcUpdateService.UpdateRoot,
                "backups", "pc-v" + plan.Version + "-" + DateTime.UtcNow.ToString("yyyyMMddHHmmss"));
            CopyWithRollback(plan.StageDirectory, plan.InstallDirectory, backup);
            AppStore.Log("PC_UPDATE_APPLIED version=" + plan.Version +
                " backup=" + backup);
            LaunchInstalled(plan.InstallDirectory);
            return 0;
        }
        catch (Exception ex)
        {
            AppStore.Log("PC_UPDATE_APPLY_FAILED " + ex);
            return 1;
        }
    }

    internal static Plan ReadAndValidate(string planFile)
    {
        string updates = WithSeparator(Path.GetFullPath(PcUpdateService.UpdateRoot));
        string absolutePlan = Path.GetFullPath(planFile);
        if (!absolutePlan.StartsWith(updates, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(Path.GetFileName(absolutePlan), "apply.json",
                StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("Unexpected updater plan location");
        Plan plan = JsonSerializer.Deserialize<Plan>(File.ReadAllText(absolutePlan))
            ?? throw new InvalidDataException("Empty update plan");
        if (plan.OldPid <= 0 || plan.OldPid == Environment.ProcessId ||
            DateTime.UtcNow - plan.CreatedUtc > TimeSpan.FromHours(2) ||
            plan.CreatedUtc > DateTime.UtcNow.AddMinutes(2) ||
            !Version.TryParse(plan.Version, out var targetVersion) ||
            targetVersion.Major != PcUpdateService.InstalledVersion.Major ||
            targetVersion.Minor != PcUpdateService.InstalledVersion.Minor ||
            targetVersion.Build != PcUpdateService.InstalledVersion.Build)
            throw new InvalidDataException("Stale, malformed or mismatched update plan");
        string stage = Path.GetFullPath(plan.StageDirectory);
        if (!stage.StartsWith(updates, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(Path.GetFileName(stage), "package",
                StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(Path.GetFullPath(Environment.ProcessPath ?? ""),
                Path.Combine(stage, "FileHubAIO.exe"),
                StringComparison.OrdinalIgnoreCase))
            throw new UnauthorizedAccessException("Helper must run from verified staging directory");
        string install = Path.GetFullPath(plan.InstallDirectory);
        if (install.StartsWith(updates, StringComparison.OrdinalIgnoreCase) ||
            string.Equals(install, stage, StringComparison.OrdinalIgnoreCase) ||
            !File.Exists(Path.Combine(install, "FileHubAIO.exe")) ||
            !Directory.Exists(Path.Combine(install, "tools")))
            throw new UnauthorizedAccessException("Unexpected destination for program update");
        EnsureNoReparse(install);
        EnsureNoReparse(stage);
        return plan with { InstallDirectory = install, StageDirectory = stage };
    }

    private static string WithSeparator(string path) =>
        path.TrimEnd('\\', '/') + Path.DirectorySeparatorChar;

    private static void EnsureNoReparse(string path)
    {
        if ((File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
            throw new UnauthorizedAccessException("Updater may not follow linked directory");
    }

    private static bool WaitForOldInstance(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            return process.WaitForExit((int)TimeSpan.FromMinutes(2).TotalMilliseconds);
        }
        catch (ArgumentException) { return true; }
    }

    // Only files shipped in the new release are replaced. Files not in the
    // release (e.g. a user's README, custom add-on or local backup) are kept.
    // Each overwritten file is saved before copying. Failures roll back.
    internal static void CopyWithRollback(string stage, string install, string backup)
    {
        string stageRoot = WithSeparator(Path.GetFullPath(stage));
        string installRoot = WithSeparator(Path.GetFullPath(install));
        string backupRoot = WithSeparator(Path.GetFullPath(backup));
        Directory.CreateDirectory(backupRoot);
        var modified = new List<(string Target, string? Previous)>();
        var files = Directory.EnumerateFiles(stageRoot, "*",
            SearchOption.AllDirectories).ToArray();
        if (files.Length is < 8 or > 3000)
            throw new IOException("Unexpected number of update files");
        try
        {
            foreach (string stagedFile in files)
            {
                if ((File.GetAttributes(stagedFile) & FileAttributes.ReparsePoint) != 0)
                    throw new UnauthorizedAccessException("Linked package file rejected");
                string relative = Path.GetRelativePath(stageRoot, stagedFile);
                if (Path.IsPathRooted(relative) ||
                    relative.StartsWith("..", StringComparison.Ordinal))
                    throw new UnauthorizedAccessException("Update path escapes package");
                string target = Path.GetFullPath(Path.Combine(installRoot, relative));
                if (!target.StartsWith(installRoot, StringComparison.OrdinalIgnoreCase))
                    throw new UnauthorizedAccessException("Update path escapes install directory");
                string? parent = Path.GetDirectoryName(target);
                if (parent is null) throw new InvalidDataException("Invalid target path");
                // Reject links in any existing intermediate path rather than
                // following them into another application or data directory.
                string scan = install.TrimEnd('\\', '/');
                foreach (string part in relative.Split(Path.DirectorySeparatorChar)
                    .SkipLast(1))
                {
                    scan = Path.Combine(scan, part);
                    if (Directory.Exists(scan)) EnsureNoReparse(scan);
                }
                Directory.CreateDirectory(parent);
                if (File.Exists(target) &&
                    (File.GetAttributes(target) & FileAttributes.ReparsePoint) != 0)
                    throw new UnauthorizedAccessException("Linked target file rejected");
                string? previous = null;
                if (File.Exists(target))
                {
                    previous = Path.Combine(backupRoot, relative);
                    Directory.CreateDirectory(Path.GetDirectoryName(previous)!);
                    File.Copy(target, previous, overwrite: false);
                }
                modified.Add((target, previous));
                File.Copy(stagedFile, target, overwrite: true);
            }
        }
        catch
        {
            foreach (var item in modified.AsEnumerable().Reverse())
            {
                try
                {
                    if (item.Previous is not null)
                        File.Copy(item.Previous, item.Target, overwrite: true);
                    else if (File.Exists(item.Target))
                        File.Delete(item.Target);
                }
                catch (Exception ex)
                {
                    AppStore.Log("PC_UPDATE_ROLLBACK_FILE_FAILED " +
                        Path.GetFileName(item.Target) + " " + ex.GetType().Name);
                }
            }
            throw;
        }
    }

    private static void LaunchInstalled(string directory)
    {
        var psi = new ProcessStartInfo(Path.Combine(directory, "FileHubAIO.exe"))
        {
            UseShellExecute = true, WorkingDirectory = directory
        };
        using var process = Process.Start(psi) ??
            throw new IOException("Update applied but restart failed");
    }

    internal static void LocalPolicyTest()
    {
        string root = Path.Combine(Path.GetTempPath(),
            "FileHubPcUpdaterQA_" + Guid.NewGuid().ToString("N"));
        string stage = Path.Combine(root, "stage");
        string live = Path.Combine(root, "live");
        Directory.CreateDirectory(stage);
        Directory.CreateDirectory(Path.Combine(stage, "tools"));
        Directory.CreateDirectory(live);
        try
        {
            File.WriteAllText(Path.Combine(stage, "FileHubAIO.exe"), "NEW");
            File.WriteAllText(Path.Combine(stage, "tools", "rclone.exe"), "UPDATED");
            File.WriteAllText(Path.Combine(live, "FileHubAIO.exe"), "OLD");
            File.WriteAllText(Path.Combine(live, "my-user-file.txt"), "KEEP");
            for (int i = 0; i < 6; i++)
                File.WriteAllText(Path.Combine(stage, "file" + i + ".txt"), "test");
            CopyWithRollback(stage, live, Path.Combine(root, "backup"));
            if (File.ReadAllText(Path.Combine(live, "FileHubAIO.exe")) != "NEW" ||
                File.ReadAllText(Path.Combine(root, "backup", "FileHubAIO.exe")) != "OLD" ||
                File.ReadAllText(Path.Combine(live, "my-user-file.txt")) != "KEEP")
                throw new IOException("Updater overwrote user data or failed to back up binary");
            Console.WriteLine("PC_UPDATE_APPLY_PRESERVES_USER_FILES_AND_BACKUPS_PASS");
        }
        finally { Directory.Delete(root, recursive: true); }
    }
}
