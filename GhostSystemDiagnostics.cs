using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace NASMount;

/// <summary>
/// Read-only forensic report of persistent WinFsp / Explorer network places.
/// Users see different state depending on the logon session, elevation and
/// stale WinFsp driver volumes. No cleanup commands are run by this class.
/// </summary>
internal static class GhostSystemDiagnostics
{
    private const string MountPoints =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2";
    private const string Network = @"Network";
    private static readonly Regex NasId = new(
        @"(?i)NASMount-([0-9a-f]{8})",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    [DllImport("mpr.dll", EntryPoint = "WNetGetConnectionW",
        CharSet = CharSet.Unicode)]
    private static extern int WNetGetConnection(string localName,
        StringBuilder remoteName, ref int size);

    [DllImport("kernel32.dll", EntryPoint = "QueryDosDeviceW",
        CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint QueryDosDevice(string deviceName,
        StringBuilder targetPath, int maxChars);

    internal static IReadOnlySet<string>? WinFspVolumeIds()
    {
        string? text = ReadWinFspVolumes();
        if (text is null) return null;
        return NasId.Matches(text).Select(x => x.Groups[1].Value)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
    }

    internal static string? ReadWinFspVolumes()
    {
        try
        {
            string exe = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
                "WinFsp", "bin", "fsptool-x64.exe");
            if (!File.Exists(exe)) return null;
            using var child = new Process();
            child.StartInfo = new ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            child.StartInfo.ArgumentList.Add("lsvol");
            child.Start();
            var stdout = child.StandardOutput.ReadToEndAsync();
            var stderr = child.StandardError.ReadToEndAsync();
            if (!child.WaitForExit(6000))
            {
                try { child.Kill(); } catch { }
                return null;
            }
            string output = stdout.GetAwaiter().GetResult();
            _ = stderr.GetAwaiter().GetResult();
            return child.ExitCode == 0 ? output : null;
        }
        catch (Exception ex)
        {
            AppStore.Log("WINFSP_VOLUME_DIAG_FAILED " + ex.Message);
            return null;
        }
    }

    private static string Hex8(string input)
    {
        var match = NasId.Match(input);
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : "";
    }

    // Shell.Application.NameSpace(17).Items() can block indefinitely while
    // another WinFsp-backed Explorer view is waiting on its provider. Run
    // this exact read-only COM query in a disposable NASMount helper process
    // so a diagnostic never hangs the main UI or test launcher.
    internal static void PrintShellFolderProbe()
    {
        try
        {
            Type? type = Type.GetTypeFromProgID("Shell.Application");
            dynamic? shell = type is null ? null : Activator.CreateInstance(type);
            dynamic? computer = shell?.NameSpace(17);
            if (computer is null)
            {
                Console.WriteLine("UNAVAILABLE: Shell NameSpace(17)");
                return;
            }
            int found = 0;
            int count = 0;
            foreach (dynamic item in computer.Items())
            {
                if (++count > 140)
                {
                    Console.WriteLine("SHELL_ENUM_TRUNCATED_AFTER_140");
                    break;
                }
                string name = (string?)item.Name ?? "";
                string path = (string?)item.Path ?? "";
                if (!name.Contains("NASMount-", StringComparison.OrdinalIgnoreCase)
                    && !path.Contains("NASMount-", StringComparison.OrdinalIgnoreCase))
                    continue;
                found++;
                Console.WriteLine("id=" + Hex8(name + " " + path) +
                    " NAME=" + name + " PATH=" + path +
                    " TYPE=" + ((string?)item.Type ?? ""));
            }
            if (found == 0)
                Console.WriteLine("No NASMount-xxxxxxxx entries visible");
        }
        catch (Exception ex)
        {
            Console.WriteLine("SHELL_ENUM_ERROR " + ex.GetType().Name +
                ": " + ex.Message);
        }
    }

    private static IReadOnlyList<string> ShellFolderLinesFromBoundedChild()
    {
        try
        {
            string? exe = Environment.ProcessPath;
            if (string.IsNullOrWhiteSpace(exe))
                return ["SHELL_ENUM_UNAVAILABLE: cannot determine own executable"];
            using var child = new Process();
            child.StartInfo = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            child.StartInfo.ArgumentList.Add("--ghost-shell-probe-child");
            child.Start();
            var output = child.StandardOutput.ReadToEndAsync();
            var errors = child.StandardError.ReadToEndAsync();
            if (!child.WaitForExit(2500))
            {
                // Only our short-lived read-only diagnostic helper, never
                // a user rclone, NASMount GUI, file lock or WinFsp mount.
                try { child.Kill(entireProcessTree: false); }
                catch (Exception ex)
                {
                    AppStore.Log("SHELL_PROBE_CHILD_TERMINATE_WARNING " +
                        ex.GetType().Name);
                }
                child.WaitForExit(1000);
                return ["SHELL_ENUM_SKIPPED_TIMEOUT_2500MS (live WinFsp Explorer may be busy)"];
            }
            string text = output.GetAwaiter().GetResult();
            _ = errors.GetAwaiter().GetResult();
            if (child.ExitCode != 0)
                return ["SHELL_ENUM_CHILD_EXIT_" + child.ExitCode];
            return text.Split('\n',
                StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
                .Take(140).ToArray();
        }
        catch (Exception ex)
        {
            return ["SHELL_ENUM_CHILD_ERROR " + ex.GetType().Name +
                ": " + ex.Message];
        }
    }

    internal static string BuildReport()
    {
        var lines = new List<string>
        {
            "FileHub AIO v1.3.78 - Windows disconnected WinFsp network place forensic report",
            "Time: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss zzz"),
            "EXE: " + Environment.ProcessPath,
            "ProcessId: " + Environment.ProcessId,
            "Windows Session ID: " + Process.GetCurrentProcess().SessionId,
            "Windows Identity: " + System.Security.Principal.WindowsIdentity.GetCurrent().Name,
            "Elevated Admin Token: " +
                new System.Security.Principal.WindowsPrincipal(
                    System.Security.Principal.WindowsIdentity.GetCurrent())
                    .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator),
            "This report reads local Windows drive/provider state; it never changes it.",
            "No cloud credentials, browser cookies or document data is collected.",
            ""
        };
        void Section(string name) { lines.Add(""); lines.Add("== " + name + " =="); }

        Section("WinFsp kernel volume list (fsptool lsvol, read-only)");
        string? fsps = ReadWinFspVolumes();
        if (fsps is null) lines.Add("UNAVAILABLE (WinFsp tool missing, timeout or error)");
        else
        {
            foreach (string line in fsps.Split('\n',
                StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                if (line.Contains("NASMount-", StringComparison.OrdinalIgnoreCase))
                    lines.Add(line);
            if (!fsps.Contains("NASMount-", StringComparison.OrdinalIgnoreCase))
                lines.Add("No NASMount volumes in WinFsp driver table");
        }

        Section("Explorer This PC Shell namespace (Name / Path / Type)");
        lines.AddRange(ShellFolderLinesFromBoundedChild());

        Section("Retired NASMount GUID evidence in last 30 days of local logs");
        var history = RetiredMountHistory.ScanRecentLocalLogs();
        lines.Add(history.Count == 0 ?
            "No identifiers found in bounded local app history" :
            string.Join(", ", history.OrderBy(x => x, StringComparer.Ordinal)));
        Section("HKCU Explorer MountPoints2 exact NASMount entries");
        using (var records = Registry.CurrentUser.OpenSubKey(MountPoints))
        {
            string[] keys = (records?.GetSubKeyNames() ?? [])
                .Where(s => s.StartsWith("##NASMount-", StringComparison.OrdinalIgnoreCase))
                .ToArray();
            if (keys.Length == 0) lines.Add("No matching MountPoints2 entries");
            foreach (string name in keys)
                lines.Add(name);
        }

        Section("HKCU saved network mappings (only NASMount UNC)");
        using (var records = Registry.CurrentUser.OpenSubKey(Network))
        {
            int found = 0;
            foreach (string letter in records?.GetSubKeyNames() ?? [])
            {
                if (letter.Length != 1) continue;
                using var key = records?.OpenSubKey(letter);
                string remote = key?.GetValue("RemotePath") as string ?? "";
                if (!remote.Contains("NASMount-", StringComparison.OrdinalIgnoreCase))
                    continue;
                lines.Add(letter.ToUpperInvariant() + ": -> " + remote);
                found++;
            }
            if (found == 0) lines.Add("No saved NASMount mappings");
        }

        Section("Machine DOS drive assignments (names only, read-only)");
        try
        {
            using var mount = Registry.LocalMachine.OpenSubKey(@"SYSTEM\MountedDevices");
            foreach (string name in mount?.GetValueNames() ?? [])
                if (Regex.IsMatch(name, @"^\\DosDevices\\[A-Za-z]:$"))
                    lines.Add(name);
            using var session = Registry.LocalMachine.OpenSubKey(
                @"SYSTEM\CurrentControlSet\Control\Session Manager\DOS Devices");
            foreach (string name in session?.GetValueNames() ?? [])
                if (name.Length == 2 && name[1] == ':')
                    lines.Add("SESSION_DOS_DEVICE=" + name);
        }
        catch (Exception ex) { lines.Add("MACHINE_DOS_QUERY_ERROR " + ex.Message); }

        Section("Explorer This PC registered NameSpace IDs (names only)");
        try
        {
            using var ns = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Explorer\MyComputer\NameSpace");
            foreach (string name in ns?.GetSubKeyNames() ?? [])
                lines.Add(name);
        }
        catch (Exception ex) { lines.Add("NAMESPACE_QUERY_ERROR " + ex.Message); }

        Section("Windows drive-letter provider (WNet/GetDriveType/QueryDosDevice)");
        for (char letter = 'A'; letter <= 'Z'; letter++)
        {
            string drive = letter + ":";
            var remote = new StringBuilder(2048);
            int length = remote.Capacity;
            int status = WNetGetConnection(drive, remote, ref length);
            var dos = new StringBuilder(8192);
            bool exists = QueryDosDevice(drive, dos, dos.Capacity) != 0;
            string target = exists ? dos.ToString().Split('\0')[0] : "";
            if (status == 0 && remote.ToString().Contains("NASMount-",
                    StringComparison.OrdinalIgnoreCase) ||
                target.Contains("WinFsp", StringComparison.OrdinalIgnoreCase) ||
                target.Contains("NASMount-", StringComparison.OrdinalIgnoreCase))
                lines.Add(drive + " WNetCode=" + status +
                    " REMOTE=" + (status == 0 ? remote.ToString() : "") +
                    " DOS_DEVICE=" + target);
        }

        foreach (int scope in new[] {
            WindowsNetworkResources.Connected,
            WindowsNetworkResources.Remembered
        })
        {
            Section(scope == WindowsNetworkResources.Connected ?
                "Windows network provider: RESOURCE_CONNECTED (MPR)" :
                "Windows network provider: RESOURCE_REMEMBERED (MPR)");
            var resources = WindowsNetworkResources.TryEnumerate(scope);
            if (resources is null)
            {
                lines.Add("UNAVAILABLE: network provider did not return enumeration");
                continue;
            }
            int matches = 0;
            foreach (var item in resources)
                if (item.NasMountId is string id)
                {
                    matches++;
                    lines.Add("id=" + id + " local=" + item.LocalName +
                        " remote=" + item.RemoteName +
                        " provider=" + item.Provider);
                }
            if (matches == 0)
                lines.Add("NO NASMount-xxxxxxxx entries (total provider records " +
                    resources.Count + ")");
        }

        Section("User Network Shortcuts (names only)");
        try
        {
            string shortcuts = Path.Combine(Environment.GetFolderPath(
                Environment.SpecialFolder.ApplicationData),
                "Microsoft", "Windows", "Network Shortcuts");
            if (!Directory.Exists(shortcuts))
                lines.Add("Network Shortcuts folder not found");
            else
            {
                string[] dirs = Directory.GetFileSystemEntries(shortcuts);
                foreach (string entry in dirs) lines.Add(Path.GetFileName(entry));
                if (dirs.Length == 0) lines.Add("No Network Shortcuts");
            }
        }
        catch (Exception ex) { lines.Add("SHORTCUTS_ENUM_ERROR " + ex.Message); }

        Section("Active NASMount processes (IDs only, no command-line secrets)");
        try
        {
            foreach (string name in new[] {
                "NASMount", "rclone", "rclone-terabox", "NASMountLockBridge"})
            {
                foreach (var process in Process.GetProcessesByName(name))
                {
                    try
                    {
                        lines.Add(name + ".exe PID=" + process.Id +
                            " SESSION=" + process.SessionId);
                    }
                    finally { process.Dispose(); }
                }
            }
        }
        catch (Exception ex) { lines.Add("PROCESS_ENUM_ERROR " + ex.Message); }
        Section("Safety");
        lines.Add("If fsptool lists a disconnected NASMount volume, registry-only cleanup cannot unmount it.");
        lines.Add("Never run fsptool unload while another WinFsp drive is connected or uploading.");
        lines.Add("If driver list and provider memory are clear but Explorer still shows old entries, Explorer may be displaying cached Shell items.");
        lines.Add("Restart ONLY Explorer after closing Explorer file-copy dialogs; never unload WinFsp or terminate rclone uploads.");
        lines.Add("All queries are local; no network requests or server writes.");
        return string.Join(Environment.NewLine, lines) + Environment.NewLine;
    }

    internal static string SaveReport()
    {
        string folder = Path.Combine(AppStore.DirectoryPath, "diagnostics");
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder,
            "NASMount_Ghost_Diagnosis_" +
            DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".txt");
        // The complete report still calls provider/registry/WinFsp APIs that
        // can block when an already-open Explorer tab has an unavailable
        // network drive. Never execute its entire chain on the live GUI.
        File.WriteAllText(path, BuildReportInBoundedChild(), new UTF8Encoding(true));
        AppStore.Log("GHOST_DIAGNOSTICS_SAVED " + path);
        return path;
    }

    private static string BuildReportInBoundedChild()
    {
        string? executable = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(executable))
            return "FileHub AIO diagnostic unavailable: own EXE path unknown." +
                Environment.NewLine;
        try
        {
            using var child = new Process();
            child.StartInfo = new ProcessStartInfo(executable)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            };
            child.StartInfo.ArgumentList.Add("--ghost-report-build-child");
            child.Start();
            Task<string> output = child.StandardOutput.ReadToEndAsync();
            Task<string> errors = child.StandardError.ReadToEndAsync();
            if (!child.WaitForExit(14000))
            {
                try { child.Kill(entireProcessTree: false); }
                catch (Exception ex)
                {
                    AppStore.Log("GHOST_REPORT_CHILD_KILL_WARNING " +
                        ex.GetType().Name);
                }
                child.WaitForExit(1000);
                AppStore.Log("GHOST_REPORT_SKIPPED_TIMEOUT_14000MS");
                return "FileHub AIO forensic report partial (no remote files changed)" +
                    Environment.NewLine +
                    "GHOST_REPORT_SKIPPED_TIMEOUT_14000MS" +
                    Environment.NewLine +
                    "A local Shell or Windows network provider was unresponsive." +
                    Environment.NewLine +
                    "Close only the offending Explorer window when safe, then " +
                    "retry the read-only diagnostic. Do not force-stop rclone." +
                    Environment.NewLine;
            }
            string result = output.GetAwaiter().GetResult();
            _ = errors.GetAwaiter().GetResult();
            if (child.ExitCode == 0 && !string.IsNullOrWhiteSpace(result))
                return result;
            return "GHOST_REPORT_CHILD_FAILED exit=" + child.ExitCode +
                Environment.NewLine + result;
        }
        catch (Exception ex)
        {
            return "GHOST_REPORT_CHILD_ERROR " + ex.GetType().Name + ": " +
                ex.Message + Environment.NewLine;
        }
    }
}
