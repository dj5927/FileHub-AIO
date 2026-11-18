using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace NASMount;

/// <summary>
/// Removes disconnected WinFsp network places left by an OLD profile GUID.
/// This intentionally does not require a matching saved NasProfile: the
/// original cloud profile may have been deleted/recreated with a new GUID.
/// Never unmounts a live filesystem, never deletes a remote file, and never
/// touches names not beginning with our unique NASMount-GUID8 UNC signature.
/// </summary>
internal static class LegacyNetworkGhostCleaner
{
    private const string Network = @"Network";
    private const string MountPoints = @"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2";
    private const uint ConnectUpdateProfile = 1;
    private const int ErrorNotConnected = 2250;
    private static readonly Regex OwnedUnc = new(
        @"^\\\\NASMount-([0-9a-fA-F]{8})(?:-[0-9a-fA-F]{8})?\\",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex OwnedMountPoint = new(
        @"^##NASMount-([0-9a-fA-F]{8})(?:-[0-9a-fA-F]{8})?#",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);
    private static readonly Regex VisibleName = new(
        @"(?:\\\\|\\)NASMount-([0-9a-fA-F]{8})(?:-[0-9a-fA-F]{8})?(?:\\|\))",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant | RegexOptions.Compiled);

    [DllImport("mpr.dll", EntryPoint = "WNetGetConnectionW", CharSet = CharSet.Unicode)]
    private static extern int WNetGetConnection(string localName,
        StringBuilder remoteName, ref int length);
    [DllImport("mpr.dll", EntryPoint = "WNetCancelConnection2W", CharSet = CharSet.Unicode)]
    private static extern int WNetCancelConnection2(string name, uint flags, bool force);

    internal sealed record Summary(int Found, int RemovedRecords,
        int CancelledMappings, int Preserved, int StillShown)
    {
        internal string Korean =>
            "과거 FileHub AIO 연결 " + Found + "개 검사\n" +
            "정리한 Windows 기록 " + RemovedRecords + "개\n" +
            "해제한 저장 매핑 " + CancelledMappings + "개\n" +
            "안전상 유지 " + Preserved + "개\n" +
            "Shell 신규 조회에서 남은 항목 " + StillShown + "개\n\n" +
            (StillShown > 0 ?
                "남은 항목은 실행 중이거나 Windows가 아직 사용 중입니다. 로그를 확인하세요." :
                "조회 가능한 연결 기록을 정리했습니다. 열린 탐색기의 빨간 X가 계속 남으면 정밀 진단을 저장하세요.");
    }

    private static string? SavedRemote(string letter)
    {
        using var key = Registry.CurrentUser.OpenSubKey(Network + "\\" + letter);
        return key?.GetValue("RemotePath") as string;
    }

    private static string? LiveRemote(string letter)
    {
        var text = new StringBuilder(2048);
        int length = text.Capacity;
        return WNetGetConnection(letter + ":", text, ref length) == 0 ?
            text.ToString() : null;
    }

    private static string? Prefix(string? remote)
    {
        var match = OwnedUnc.Match(remote ?? "");
        return match.Success ? match.Groups[1].Value.ToLowerInvariant() : null;
    }

    private static bool LetterReady(string letter)
    {
        try { return new DriveInfo(letter + ":\\").IsReady; }
        catch (IOException) { return false; }
        catch (UnauthorizedAccessException) { return true; } // fail closed
        catch (Exception) { return true; }
    }

    private static IEnumerable<(string Letter, string Remote)> SavedMappings()
    {
        using var root = Registry.CurrentUser.OpenSubKey(Network);
        foreach (var name in root?.GetSubKeyNames() ?? [])
        {
            if (name.Length != 1 || !char.IsLetter(name[0])) continue;
            string letter = name.ToUpperInvariant();
            string? remote = SavedRemote(letter);
            if (Prefix(remote) is not null)
                yield return (letter, remote!);
        }
    }

    private sealed record VisibleItem(string Guid8, string Letter,
        string Title, string Path);

    private static List<VisibleItem> VisibleOldItems()
    {
        var output = new List<VisibleItem>();
        try
        {
            Type? shellType = Type.GetTypeFromProgID("Shell.Application");
            dynamic? shell = shellType is null ? null : Activator.CreateInstance(shellType);
            dynamic? computer = shell?.NameSpace(17);
            if (computer is null) return output;
            foreach (dynamic item in computer.Items())
            {
                string name = (string?)item.Name ?? "";
                string path = (string?)item.Path ?? "";
                var match = VisibleName.Match(name + " " + path);
                if (match.Success)
                {
                    string letter = path.Length >= 2 &&
                        char.IsLetter(path[0]) && path[1] == ':' ?
                        path[..1].ToUpperInvariant() : "";
                    output.Add(new VisibleItem(match.Groups[1].Value.ToLowerInvariant(),
                        letter, name, path));
                    AppStore.Log("OLD_GHOST_SHELL_VISIBLE id=" +
                        match.Groups[1].Value + " letter=" + letter +
                        " name=" + name + " path=" + path);
                }
            }
        }
        catch (Exception ex)
        {
            AppStore.Log("OLD_GHOST_SHELL_ENUM_WARNING " + ex.Message);
        }
        return output;
    }

    private static string[] HistoryKeysFor(string guid8)
    {
        using var key = Registry.CurrentUser.OpenSubKey(MountPoints);
        return (key?.GetSubKeyNames() ?? [])
            .Where(name => OwnedMountPoint.Match(name) is var m &&
                m.Success &&
                m.Groups[1].Value.Equals(guid8,
                    StringComparison.OrdinalIgnoreCase)).ToArray();
    }

    private static HashSet<string>? ActiveBackendPrefixes()
    {
        try
        {
            if (!new[] { "rclone", "rclone-terabox", "NASMountLockBridge" }
                .Any(n => Process.GetProcessesByName(n).Length > 0))
                return new(StringComparer.OrdinalIgnoreCase);
            using var child = new Process();
            child.StartInfo = new ProcessStartInfo("powershell.exe") {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            child.StartInfo.ArgumentList.Add("-NoProfile");
            child.StartInfo.ArgumentList.Add("-NonInteractive");
            child.StartInfo.ArgumentList.Add("-Command");
            child.StartInfo.ArgumentList.Add(
                "$ErrorActionPreference='Stop';" +
                "$p=Get-CimInstance Win32_Process -Filter " +
                "\"Name='rclone.exe' OR Name='rclone-terabox.exe' " +
                "OR Name='NASMountLockBridge.exe'\";" +
                "foreach($x in $p){if(!$x.CommandLine){throw 'unreadable process commandline'};" +
                "foreach($m in [regex]::Matches($x.CommandLine," +
                "'(?i)(?:nm_|NASMount-|--profile[ =]+)([0-9a-f]{8})')){" +
                "$m.Groups[1].Value.ToLowerInvariant()}}");
            child.Start();
            if (!child.WaitForExit(8000))
            {
                try { child.Kill(); } catch { }
                AppStore.Log("OLD_GHOST_PROCESS_SCAN_TIMEOUT");
                return null;
            }
            string stdout = child.StandardOutput.ReadToEnd();
            if (child.ExitCode != 0)
            {
                AppStore.Log("OLD_GHOST_PROCESS_SCAN_FAILURE code=" + child.ExitCode);
                return null;
            }
            return stdout.Split(['\r', '\n'],
                    StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                .Where(id => Regex.IsMatch(id, @"^[0-9a-f]{8}$"))
                .ToHashSet(StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex)
        {
            AppStore.Log("OLD_GHOST_PROCESS_SCAN_EXCEPTION " + ex.Message);
            return null;
        }
    }

    internal static void SessionNamePolicyTest()
    {
        const string guid8 = "a89b80a7";
        if (Prefix(@"\\NASMount-a89b80a7-937c6f96\TeraBox") != guid8 ||
            Prefix(@"\\NASMount-a89b80a7\TeraBox") != guid8 ||
            !OwnedMountPoint.IsMatch("##NASMount-a89b80a7-937c6f96#TeraBox") ||
            !OwnedMountPoint.IsMatch("##NASMount-a89b80a7#TeraBox") ||
            !VisibleName.IsMatch(@"Ter(\\NASMount-a89b80a7-937c6f96)(V:)") ||
            Prefix(@"\\Other-12345678\TeraBox") is not null ||
            OwnedMountPoint.IsMatch("##NASMount-a89b80a7-unsafe#TeraBox"))
            throw new InvalidOperationException(
                "Session-suffixed owned network ghosts must be recognized precisely");
        Console.WriteLine("NETWORK_SESSION_GHOST_PATTERN_PASS");
    }

    internal static Summary Cleanup(IReadOnlyCollection<NasProfile> profiles,
        string? onlyGuid8 = null, bool includeHistorical = true)
    {
        var found = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using (var points = Registry.CurrentUser.OpenSubKey(MountPoints))
            foreach (string name in points?.GetSubKeyNames() ?? [])
            {
                var match = OwnedMountPoint.Match(name);
                if (match.Success) found.Add(match.Groups[1].Value);
            }
        foreach (var (_, remote) in SavedMappings())
            if (Prefix(remote) is string id) found.Add(id);
        var rememberedResources = WindowsNetworkResources.TryEnumerate(
            WindowsNetworkResources.Remembered);
        var connectedResources = WindowsNetworkResources.TryEnumerate(
            WindowsNetworkResources.Connected);
        foreach (var item in rememberedResources ?? [])
            if (item.NasMountId is string id) found.Add(id);
        foreach (var item in connectedResources ?? [])
            if (item.NasMountId is string id) found.Add(id);
        var shellItems = VisibleOldItems();
        foreach (var item in shellItems) found.Add(item.Guid8);
        // A fresh Shell namespace can return zero items while a previously
        // opened Explorer tab still displays a retired red-X network tile.
        // Recover our OWN old server IDs from bounded local NASMount logs.
        // This is discovery, never proof of disconnection: preserve all
        // active-process, network-provider and WinFsp safety gates below.
        var historical = includeHistorical
            ? RetiredMountHistory.ScanRecentLocalLogs()
            : new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        found.UnionWith(historical);
        AppStore.Log("OLD_GHOST_HISTORICAL_ID_SCAN count=" + historical.Count +
            " totalCandidates=" + found.Count);
        if (onlyGuid8 is not null)
        {
            if (!Regex.IsMatch(onlyGuid8, @"^[a-fA-F0-9]{8}$"))
                throw new ArgumentException("Invalid NASMount 8-digit GUID prefix");
            found.IntersectWith([onlyGuid8]);
        }

        var activeBackends = ActiveBackendPrefixes();
        // WinFsp kernel can retain a volume after its rclone owner exits.
        // Never erase its evidence or mapping while the driver still owns it.
        var driverVolumes = GhostSystemDiagnostics.WinFspVolumeIds();
        int removed = 0, cancelled = 0, preserved = 0;
        var locked = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string id in found)
        {
            int previouslyRemoved = removed;
            int previouslyCancelled = cancelled;
            // A failed driver/provider query must not be interpreted as
            // evidence that a former volume is now safe to remove.
            if (driverVolumes is null || connectedResources is null)
            {
                AppStore.Log("OLD_GHOST_PRESERVE_UNKNOWN_DRIVER_OR_PROVIDER " + id);
                preserved++; locked.Add(id); continue;
            }
            // A running old-version rclone may still have dirty queued
            // uploads; skip the ENTIRE GUID until it terminates safely.
            // Also recognize current saved profiles, even if letter changed.
            if (profiles.Any(p =>
                p.Id.ToString("N").StartsWith(id, StringComparison.OrdinalIgnoreCase)
                && (RemoteMount.HasManagedRecord(p) ||
                    RemoteMount.IsRunning(p))))
            {
                AppStore.Log("OLD_GHOST_PRESERVE_MANAGED " + id);
                preserved++; locked.Add(id); continue;
            }
            if (activeBackends is null || activeBackends.Contains(id))
            {
                AppStore.Log("OLD_GHOST_PRESERVE_BACKEND_PROCESS " + id);
                preserved++; locked.Add(id); continue;
            }
            // Windows network providers expose connected and remembered
            // resources separately. Never cancel a RESOURCE_CONNECTED UNC,
            // even when WNetGetConnection on its old drive letter failed.
            if (connectedResources?.Any(item => string.Equals(
                    item.NasMountId, id, StringComparison.OrdinalIgnoreCase)) == true)
            {
                AppStore.Log("OLD_GHOST_PRESERVE_MPR_CONNECTED " + id);
                preserved++; locked.Add(id); continue;
            }
            if (driverVolumes?.Contains(id) == true)
            {
                AppStore.Log("OLD_GHOST_PRESERVE_WIN_FSP_KERNEL_VOLUME " + id);
                preserved++; locked.Add(id); continue;
            }
            bool active = false;
            for (char c = 'A'; c <= 'Z'; c++)
            {
                string letter = c.ToString();
                bool mappedToId = string.Equals(Prefix(SavedRemote(letter)), id,
                        StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(Prefix(LiveRemote(letter)), id,
                        StringComparison.OrdinalIgnoreCase);
                if (mappedToId && LetterReady(letter))
                {
                    active = true;
                    AppStore.Log("OLD_GHOST_PRESERVE_LIVE_DRIVE " +
                        id + " letter=" + letter);
                    break;
                }
            }
            if (active) { preserved++; locked.Add(id); continue; }

            bool cancellationFailed = false;
            foreach (var (letter, remote) in SavedMappings().ToArray())
            {
                if (!string.Equals(Prefix(remote), id,
                        StringComparison.OrdinalIgnoreCase)) continue;
                string? current = LiveRemote(letter);
                if (current is not null && Prefix(current) != id)
                {
                    AppStore.Log("OLD_GHOST_SKIP_FOREIGN_LETTER " +
                        id + " letter=" + letter);
                    continue;
                }
                int code = WNetCancelConnection2(letter + ":",
                    ConnectUpdateProfile, force: false);
                AppStore.Log("OLD_GHOST_WNET_CANCEL " + id +
                    " letter=" + letter + " status=" + code);
                if (code != 0 && code != ErrorNotConnected)
                {
                    cancellationFailed = true; break;
                }
                if (string.Equals(SavedRemote(letter), remote,
                    StringComparison.OrdinalIgnoreCase) &&
                    !LetterReady(letter))
                {
                    Registry.CurrentUser.DeleteSubKeyTree(
                        Network + "\\" + letter,
                        throwOnMissingSubKey: false);
                }
                cancelled++;
            }
            // Some WinFsp red-X entries have a Shell P: item with the old
            // NASMount GUID but no longer have an HKCU\Network\P record.
            // Only cancel that exact, offline and unowned letter.
            if (!cancellationFailed)
                foreach (var shell in shellItems.Where(item =>
                    item.Guid8.Equals(id, StringComparison.OrdinalIgnoreCase) &&
                    item.Letter.Length == 1 &&
                    !LetterReady(item.Letter) &&
                    !SavedMappings().Any(m => m.Letter == item.Letter)))
                {
                    string? current = LiveRemote(shell.Letter);
                    if (current is not null &&
                        !string.Equals(Prefix(current), id,
                            StringComparison.OrdinalIgnoreCase))
                    {
                        AppStore.Log("OLD_GHOST_SHELL_OTHER_PROVIDER " +
                            id + " letter=" + shell.Letter);
                        continue;
                    }
                    int code = WNetCancelConnection2(shell.Letter + ":",
                        ConnectUpdateProfile, force: false);
                    AppStore.Log("OLD_GHOST_SHELL_CANCEL " +
                        id + " letter=" + shell.Letter + " status=" + code);
                    if (code != 0 && code != ErrorNotConnected)
                    {
                        cancellationFailed = true;
                        break;
                    }
                    cancelled++;
                }
            if (cancellationFailed)
            {
                preserved++; locked.Add(id); continue;
            }

            // A remembered WinFsp provider connection may survive without
            // HKCU\Network\<letter> or a current drive letter. Use the MPR's
            // own RESOURCE_REMEMBERED enumeration to cancel that exact entry.
            foreach (var resource in rememberedResources?.Where(x =>
                x.NasMountId?.Equals(id, StringComparison.OrdinalIgnoreCase) == true)
                ?? [])
            {
                string cancelName = resource.RemoteName;
                string local = resource.LocalName.TrimEnd('\\');
                if (local.Length == 2 && char.IsLetter(local[0]) && local[1] == ':')
                {
                    // Never take over a drive letter reused by RaiDrive or
                    // a current FileHub AIO profile.
                    if (LetterReady(local[..1]))
                    {
                        AppStore.Log("OLD_GHOST_MPR_REMEMBERED_LIVE_LETTER " +
                            id + " letter=" + local);
                        cancellationFailed = true;
                        break;
                    }
                    string? current = LiveRemote(local[..1]);
                    if (current is not null &&
                        !string.Equals(Prefix(current), id,
                            StringComparison.OrdinalIgnoreCase))
                    {
                        AppStore.Log("OLD_GHOST_MPR_REMEMBERED_OTHER_PROVIDER " +
                            id + " letter=" + local);
                        cancellationFailed = true;
                        break;
                    }
                    cancelName = local;
                }
                int code = WNetCancelConnection2(cancelName,
                    ConnectUpdateProfile, force: false);
                AppStore.Log("OLD_GHOST_MPR_REMEMBERED_CANCEL id=" + id +
                    " target=" + cancelName + " status=" + code);
                if (code is not (0 or ErrorNotConnected or 67))
                {
                    cancellationFailed = true;
                    break;
                }
                cancelled++;
            }
            if (cancellationFailed)
            {
                preserved++; locked.Add(id); continue;
            }

            // WinFsp network-mode records a full UNC, not only P:/Z:.
            // Cancelling its exact old UNC can release an Explorer
            // network-place entry with no HKCU\Network drive letter.
            var oldUncs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (string key in HistoryKeysFor(id))
            {
                if (!OwnedMountPoint.IsMatch(key))
                    continue;
                // Preserve the complete session-suffixed WinFsp server name.
                // Red-X history uses NASMount-GUID8-SESSION8, not just GUID8.
                string suffix = key[2..];
                if (suffix.Length == 0 || suffix.Length > 200 ||
                    suffix.Contains("..", StringComparison.Ordinal) ||
                    suffix.Contains(':') || suffix.Contains('/') ||
                    suffix.Contains('\r') || suffix.Contains('\n'))
                    continue;
                string unc = @"\\" + suffix.Replace('#', '\\');
                oldUncs.Add(unc);
            }
            // Explorer can remember a full UNC PIDL even after the
            // corresponding HKCU MountPoints2 key has vanished.
            foreach (var shell in shellItems.Where(x =>
                x.Guid8.Equals(id, StringComparison.OrdinalIgnoreCase)))
            {
                if (string.Equals(Prefix(shell.Path), id,
                        StringComparison.OrdinalIgnoreCase) &&
                    shell.Path.Length < 240 &&
                    !shell.Path.Contains("..", StringComparison.Ordinal) &&
                    !shell.Path.Contains('\r') && !shell.Path.Contains('\n'))
                    oldUncs.Add(shell.Path.TrimEnd('\\'));
            }
            foreach (string unc in oldUncs)
            {
                int code = WNetCancelConnection2(unc,
                    ConnectUpdateProfile, force: false);
                AppStore.Log("OLD_GHOST_TARGET_UNC_CANCEL id=" + id +
                    " code=" + code + " unc=" + unc);
                if (code != 0 && code != ErrorNotConnected && code != 67)
                {
                    cancellationFailed = true;
                    break;
                }
            }
            if (cancellationFailed)
            {
                preserved++; locked.Add(id); continue;
            }

            using (var points = Registry.CurrentUser.OpenSubKey(
                MountPoints, writable: true))
            {
                foreach (string key in HistoryKeysFor(id))
                {
                    points?.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
                    removed++;
                    AppStore.Log("OLD_GHOST_HISTORY_REMOVED " + id + " " + key);
                }
            }
            bool hasShellEvidence = oldUncs.Count > 0 ||
                removed != previouslyRemoved ||
                cancelled != previouslyCancelled;
            AppStore.Log("OLD_GHOST_GUID_CLEANUP_FINISHED " + id +
                " actualShellEvidence=" + hasShellEvidence);
            // Signal the exact, proven-retired NASMount server to the Shell
            // even if its registry key disappeared long ago. Never send a
            // drive-letter removal event: Windows may have reused that
            // letter for another product in the meantime.
            if (hasShellEvidence)
                ExplorerDriveIcon.NotifyRetiredNetworkServer(id, oldUncs);
            else
                AppStore.Log("OLD_GHOST_NOTIFY_SKIPPED_NO_REAL_EVIDENCE id=" + id);
        }
        if (removed > 0 || cancelled > 0)
            ExplorerDriveIcon.NotifyComputerChanged();
        var visible = VisibleOldItems();
        int still = visible.Count(item => found.Contains(item.Guid8));
        AppStore.Log("OLD_GHOST_CLEANUP_SUMMARY discovered=" + found.Count +
            " removed=" + removed + " mapped=" + cancelled +
            " protected=" + preserved + " shellStill=" + still);
        return new Summary(found.Count, removed, cancelled, preserved, still);
    }
}
