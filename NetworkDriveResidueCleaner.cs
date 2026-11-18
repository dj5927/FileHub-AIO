using System.Diagnostics;
using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Windows remembers network-mode WinFsp volumes in a DIFFERENT place from
/// GetLogicalDrives(). Removing the rclone process alone leaves red-X Explorer
/// "Network locations" entries. This cleaner only touches a verified
/// \\NASMount-{profileGuid8}\... share, never another provider or SMB.
/// </summary>
internal static class NetworkDriveResidueCleaner
{
    private const uint CONNECT_UPDATE_PROFILE = 0x00000001;
    private const int NO_ERROR = 0;
    private const int ERROR_NOT_CONNECTED = 2250;
    private const string NetworkKey = @"Network";

    [DllImport("mpr.dll", EntryPoint = "WNetGetConnectionW",
        CharSet = CharSet.Unicode)]
    private static extern int WNetGetConnection(string localName,
        StringBuilder remoteName, ref int length);

    [DllImport("mpr.dll", EntryPoint = "WNetCancelConnection2W",
        CharSet = CharSet.Unicode)]
    private static extern int WNetCancelConnection2(string name,
        uint flags, bool force);

    private static (int Code, string? Remote) CurrentConnection(string letter)
    {
        var text = new StringBuilder(2048);
        int size = text.Capacity;
        int code = WNetGetConnection(letter + ":", text, ref size);
        return (code, code == NO_ERROR ? text.ToString() : null);
    }

    private static string? RememberedConnection(string letter)
    {
        using var record = Registry.CurrentUser.OpenSubKey(
            NetworkKey + "\\" + letter);
        return record?.GetValue("RemotePath") as string;
    }

    private static bool IsForeignActiveMapping(NasProfile profile,
        (int Code, string? Remote) current) =>
        current.Code == NO_ERROR && !RemoteMount.IsOurNetworkShare(
            profile, current.Remote);

    // A persistent, DISCONNECTED network drive is still returned by
    // GetLogicalDrives(), even though it has a red X in This PC. Earlier
    // versions skipped cleanup for every letter that merely "exists".
    internal static bool IsDisconnectedOwnedGhost(NasProfile profile)
    {
        if (profile.Protocol == "SMB" || profile.Letter.Length != 1 ||
            !RemoteMount.DriveLetterExists(profile.Letter) ||
            RemoteMount.HasManagedRecord(profile)) return false;
        var current = CurrentConnection(profile.Letter.ToUpperInvariant());
        if (IsForeignActiveMapping(profile, current)) return false;
        if (!RemoteMount.IsOurNetworkShare(profile, current.Remote) &&
            !RemoteMount.IsOurNetworkShare(profile,
                RememberedConnection(profile.Letter.ToUpperInvariant())) &&
            !ExplorerDriveIcon.HasOwnedMountPointHistory(profile)) return false;
        try
        {
            var drive = new DriveInfo(profile.Letter.ToUpperInvariant() + ":\\");
            // Never remove a healthy live drive regardless of the old GUI.
            // WinFsp can expose the same disconnected UNC as Network or
            // Fixed/NTFS in different Explorer sessions; the authoritative
            // safety check above is the exact NASMount profile UNC.
            return !drive.IsReady;
        }
        catch (IOException) { return true; }
        catch (UnauthorizedAccessException) { return false; }
    }

    /// <summary>
    /// An occupied drive letter might belong to an entirely different NAS
    /// or another NASMount profile. Only exact profile-owned active mappings
    /// can block removal of an unconnected saved connection.
    /// </summary>
    internal static bool HasActiveOwnedNetworkMapping(NasProfile profile)
    {
        if (profile.Protocol == "SMB" || profile.Letter.Length != 1 ||
            !RemoteMount.DriveLetterExists(profile.Letter))
            return false;
        string letter = profile.Letter.ToUpperInvariant();
        var current = CurrentConnection(letter);
        if (IsForeignActiveMapping(profile, current))
            return false;
        bool owned = RemoteMount.IsOurNetworkShare(profile, current.Remote) ||
            RemoteMount.IsOurNetworkShare(profile, RememberedConnection(letter));
        return owned && !IsDisconnectedOwnedGhost(profile);
    }

    // A previous GUI may have exited while its rclone child still owns dirty
    // cached uploads. Check only this profile's GUID, so an unrelated drive
    // does not block cleanup of a genuinely disconnected cloud mapping.
    internal static bool OldBackendMayStillOwn(NasProfile profile)
    {
        try
        {
            string[] names = ["rclone", "rclone-terabox", "NASMountLockBridge"];
            bool any = names.Any(name => Process.GetProcessesByName(name).Any());
            if (!any) return false;
            string guid = profile.Id.ToString("N");
            var psi = new ProcessStartInfo("powershell.exe") {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            psi.ArgumentList.Add("-NoProfile");
            psi.ArgumentList.Add("-NonInteractive");
            psi.ArgumentList.Add("-Command");
            psi.ArgumentList.Add(
                "$ErrorActionPreference='Stop';" +
                "$p=Get-CimInstance Win32_Process -Filter " +
                "\"Name='rclone.exe' OR Name='rclone-terabox.exe' " +
                "OR Name='NASMountLockBridge.exe'\";" +
                "$found=@($p|Where-Object {$_.CommandLine -and (" +
                "$_.CommandLine.IndexOf('" + guid +
                "',[StringComparison]::OrdinalIgnoreCase) -ge 0 -or " +
                "$_.CommandLine.IndexOf('NASMount-" + guid[..8] +
                "',[StringComparison]::OrdinalIgnoreCase) -ge 0 -or " +
                "$_.CommandLine.IndexOf('nm_" + guid[..8] +
                "',[StringComparison]::OrdinalIgnoreCase) -ge 0 -or " +
                "$_.CommandLine.IndexOf('" + profile.Id.ToString("D") +
                "',[StringComparison]::OrdinalIgnoreCase) -ge 0)});" +
                "if($found.Count -gt 0){'ACTIVE'}else{'NONE'}");
            using var child = Process.Start(psi);
            if (child is null) return true;
            if (!child.WaitForExit(7000))
            {
                try { child.Kill(); } catch { }
                AppStore.Log("ORPHAN_BACKEND_OWNER_PROBE_TIMEOUT " + profile.Id);
                return true;
            }
            string answer = child.StandardOutput.ReadToEnd().Trim();
            if (child.ExitCode != 0 || answer is not ("NONE" or "ACTIVE"))
            {
                AppStore.Log("ORPHAN_BACKEND_OWNER_PROBE_UNKNOWN " + profile.Id);
                return true;
            }
            return answer == "ACTIVE";
        }
        catch (Exception ex)
        {
            AppStore.Log("ORPHAN_BACKEND_OWNER_PROBE_FAILED " +
                profile.Id + " " + ex.Message);
            return true;
        }
    }

    private static bool TryNasMountGuid(string? remotePath, out string guid8)
    {
        var match = Regex.Match(remotePath ?? "",
            @"^\\\\NASMount-([0-9a-fA-F]{8})(?:-[0-9a-fA-F]{8})?\\",
            RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);
        guid8 = match.Success ? match.Groups[1].Value : "";
        return match.Success;
    }

    // Called at startup. Old versions sometimes had profiles removed or
    // renamed but left disconnected NASMount-* records in Explorer. Never
    // touch an active logical drive or an unrelated provider. If any live
    // NETWORK drive has an unknown provider, fail closed on global pruning.
    internal static void PruneOldDisconnectedNasMountHistory()
    {
        try
        {
            var active = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            for (char letter = 'A'; letter <= 'Z'; letter++)
            {
                string local = letter.ToString();
                if (!RemoteMount.DriveLetterExists(local)) continue;
                var live = CurrentConnection(local);
                if (TryNasMountGuid(live.Remote, out var id)) active.Add(id);
                else if (live.Code != NO_ERROR &&
                    new DriveInfo(local + ":\\").DriveType == DriveType.Network)
                {
                    AppStore.Log("ORPHAN_NETWORK_HISTORY_PRUNE_DEFERRED " +
                        local + ": network provider unavailable");
                    return;
                }
            }
            // A remembered mapping may be present even though the logical
            // drive has disappeared. Remove exactly our NASMount-* entries.
            using (var mappings = Registry.CurrentUser.OpenSubKey(NetworkKey))
            {
                foreach (string name in mappings?.GetSubKeyNames() ?? [])
                {
                    if (name.Length != 1 || !char.IsLetter(name[0]) ||
                        RemoteMount.DriveLetterExists(name)) continue;
                    string? saved = RememberedConnection(name.ToUpperInvariant());
                    if (!TryNasMountGuid(saved, out _)) continue;
                    var live = CurrentConnection(name.ToUpperInvariant());
                    if (live.Code == NO_ERROR &&
                        !string.Equals(live.Remote, saved,
                            StringComparison.OrdinalIgnoreCase)) continue;
                    int code = WNetCancelConnection2(name.ToUpperInvariant() + ":",
                        CONNECT_UPDATE_PROFILE, force: false);
                    AppStore.Log("OLD_NETWORK_PROFILE_CANCEL " + name +
                        " status=" + code);
                    if (code is NO_ERROR or ERROR_NOT_CONNECTED)
                    {
                        // Reopen/read the key after the MPR call: Windows may
                        // have removed it, and another mapping must not be
                        // mistaken for the stale entry.
                        if (string.Equals(RememberedConnection(name.ToUpperInvariant()),
                                saved, StringComparison.OrdinalIgnoreCase))
                            Registry.CurrentUser.DeleteSubKeyTree(
                                NetworkKey + "\\" + name,
                                throwOnMissingSubKey: false);
                    }
                }
            }
            int count = ExplorerDriveIcon.PruneOldUnmountedHistory(active);
            AppStore.Log("OLD_NETWORK_HISTORY_PRUNE_COMPLETE removed=" + count +
                " liveNasMountGuids=" + active.Count);
        }
        catch (Exception ex)
        {
            AppStore.Log("OLD_NETWORK_HISTORY_PRUNE_SKIPPED " + ex.Message);
        }
    }

    private static bool RemoveRememberedKeyOnlyWhenOurs(NasProfile p)
    {
        string letter = p.Letter.ToUpperInvariant();
        using (var key = Registry.CurrentUser.OpenSubKey(NetworkKey + "\\" + letter))
        {
            if (!RemoteMount.IsOurNetworkShare(p,
                    key?.GetValue("RemotePath") as string)) return false;
        }
        // Ownership is checked again just before deleting. This key is only
        // a remembered *mapping*, not a NAS file or an rclone VFS cache.
        if (IsForeignActiveMapping(p, CurrentConnection(letter))) return false;
        Registry.CurrentUser.DeleteSubKeyTree(NetworkKey + "\\" + letter,
            throwOnMissingSubKey: false);
        AppStore.Log("NETWORK_REMEMBERED_MAPPING_FORGOTTEN " +
            p.Id + " letter=" + letter);
        return true;
    }

    /// <summary>
    /// Invoke only after the tracked rclone and optional WinFsp bridge have
    /// exited normally, with VFS uploads and leases checked clean.
    /// </summary>
    internal static OperationResult AfterSafeStop(NasProfile p)
    {
        if (p.Protocol == "SMB") return OperationResult.Ok("SMB 매핑은 유지");
        if (p.Letter.Length != 1) return OperationResult.Fail("잘못된 드라이브 문자");
        try
        {
            string letter = p.Letter.ToUpperInvariant();
            var current = CurrentConnection(letter);
            string? remembered = RememberedConnection(letter);
            bool currentOurs = RemoteMount.IsOurNetworkShare(p, current.Remote);
            bool rememberedOurs = RemoteMount.IsOurNetworkShare(p, remembered);
            bool historyGhost = !RemoteMount.IsRunning(p) &&
                ExplorerDriveIcon.HasOwnedMountPointHistory(p) &&
                IsDisconnectedOwnedGhost(p);
            if (IsForeignActiveMapping(p, current))
            {
                AppStore.Log("NETWORK_CLEANUP_FOREIGN_MOUNT_PRESERVED " + p.Id +
                    " letter=" + letter);
                // A different product took the letter. Clean only historical
                // entries tied to our GUID, NEVER the live letter mapping.
                ExplorerDriveIcon.Restore(p);
                int history = ExplorerDriveIcon.ForgetDisconnectedProfileHistory(p);
                if (history < 0)
                    return OperationResult.Fail("FileHub AIO 네트워크 기록 정리 실패");
                return OperationResult.Ok("다른 프로그램의 네트워크 연결은 유지했습니다.");
            }
            if (currentOurs || rememberedOurs || historyGhost)
            {
                // v1.3.41 used flags=0, which leaves Windows' saved mapped
                // network profile. This flag is essential for red-X removal.
                bool backendStopped = !RemoteMount.IsRunning(p);
                int result = WNetCancelConnection2(letter + ":",
                    CONNECT_UPDATE_PROFILE, force: backendStopped);
                AppStore.Log("NETWORK_WNET_CANCEL_PROFILE " + p.Id +
                    " letter=" + letter + " status=" + result +
                    " backendStopped=" + backendStopped);
                if (backendStopped && currentOurs &&
                    RemoteMount.IsOurNetworkShare(p, CurrentConnection(letter).Remote))
                {
                    int remoteStatus = WNetCancelConnection2(current.Remote!,
                        CONNECT_UPDATE_PROFILE, force: true);
                    AppStore.Log("NETWORK_WNET_CANCEL_OWNED_UNC id=" +
                        p.Id + " status=" + remoteStatus);
                }
                if (result != NO_ERROR && result != ERROR_NOT_CONNECTED)
                {
                    AppStore.Log("NETWORK_WNET_NONFATAL_STATUS " + p.Id +
                        " code=" + result);
                    if (RemoteMount.DriveLetterExists(letter) && !backendStopped)
                        return OperationResult.Fail(letter +
                            ": Windows 네트워크 연결을 다른 프로그램이 사용 중이거나" +
                            " 열린 파일이 있어 안전하게 해제하지 못했습니다." +
                            " Windows 오류=" + result);
                }
            }
            // WNet may respond ERROR_NOT_CONNECTED even while HKCU\Network
            // retains an unreachable old record. Delete only if the record's
            // RemotePath still matches this exact FileHub AIO profile.
            if (RemoteMount.IsOurNetworkShare(p, RememberedConnection(letter)))
                RemoveRememberedKeyOnlyWhenOurs(p);
            // Restore FileHub AIO-owned icon overrides, then remove the
            // UNC-specific MountPoints2 history key itself, not just _Icon.
            ExplorerDriveIcon.Restore(p);
            int removed = ExplorerDriveIcon.ForgetDisconnectedProfileHistory(p);
            if (removed < 0)
                return OperationResult.Fail("FileHub AIO 탐색기 네트워크 위치 기록 정리 실패");
            ExplorerDriveIcon.NotifyDisconnected(p);
            var still = CurrentConnection(letter);
            if (RemoteMount.IsOurNetworkShare(p, still.Remote) ||
                RemoteMount.IsOurNetworkShare(p, RememberedConnection(letter)))
                return OperationResult.Fail(
                    letter + ": 저장된 네트워크 연결이 아직 남아 있습니다." +
                    " 열린 네트워크 창 또는 사용 중인 핸들을 확인하세요.");
            // Enumerating the Shell.ThisPC COM namespace inside safe exit
            // may block for seconds if Explorer includes a slow WinFsp
            // provider. File/cache safety is already verified and current
            // WNet+registry mappings were checked above. The UI schedules
            // a best-effort This PC refresh after a successful disconnect.
            // Full Shell enumeration remains in the explicit diagnostics.
            AppStore.Log("NETWORK_SHELL_GHOST_VERIFICATION_DEFERRED " + p.Id +
                " letter=" + letter + " providerRecordsClean=true");
            AppStore.Log("NETWORK_STALE_ENTRIES_CLEAN " + p.Id +
                " letter=" + letter + " mountpoints=" + removed);
            return OperationResult.Ok("Windows 네트워크 위치 및 저장된 연결 정리 확인");
        }
        catch (Exception ex)
        {
            AppStore.Log("NETWORK_STALE_ENTRIES_CLEAN_FAILED " +
                p.Id + " " + ex);
            return OperationResult.Fail("네트워크 위치 정리 실패: " + ex.Message);
        }
    }

    // Startup migration: only when Windows reports NO mounted drive letter
    // and the caller verified no old WinFsp bridge owns the profile.
    internal static bool HasDisconnectedOwnedResidue(NasProfile p)
    {
        if (p.Protocol == "SMB" || p.Letter.Length != 1 ||
            RemoteMount.DriveLetterExists(p.Letter)) return false;
        var current = CurrentConnection(p.Letter.ToUpperInvariant());
        if (IsForeignActiveMapping(p, current)) return false;
        return RemoteMount.IsOurNetworkShare(p, current.Remote) ||
            RemoteMount.IsOurNetworkShare(p,
                RememberedConnection(p.Letter.ToUpperInvariant())) ||
            ExplorerDriveIcon.HasOwnedPresentationResidue(p);
    }

    internal static void CleanupDisconnectedStartupHistory(NasProfile p)
    {
        if (p.Protocol == "SMB" || RemoteMount.DriveLetterExists(p.Letter))
            return;
        if (!HasDisconnectedOwnedResidue(p))
        {
            AppStore.Log("STARTUP_NO_OWNED_SHELL_RESIDUE_SKIP " + p.Id);
            return;
        }
        var current = CurrentConnection(p.Letter.ToUpperInvariant());
        if (IsForeignActiveMapping(p, current)) return;
        // The caller already checked that the drive letter is absent and no
        // live bridge is present. WNetGetConnection can still report a stale
        // remembered UNC, so do not skip cleanup merely because it returns
        // an old FileHub AIO share name.
        var result = AfterSafeStop(p);
        if (!result.Success)
            AppStore.Log("NETWORK_STARTUP_RESIDUE_WARNING " + p.Id + " " + result.Message);
    }

    internal static void PolicySelfTest()
    {
        // A newly configured, never-mounted profile must not trigger
        // registry restores or desktop-wide shell invalidation on startup.
        string? freeLetter = Enumerable.Range(0, 23)
            .Select(index => ((char)('Z' - index)).ToString())
            .FirstOrDefault(letter => !RemoteMount.DriveLetterExists(letter));
        if (freeLetter is not null)
        {
            var neverMounted = new NasProfile
            {
                Id = Guid.NewGuid(), Protocol = "GoogleDrive",
                Letter = freeLetter, DisplayName = "Unused QA"
            };
            if (HasDisconnectedOwnedResidue(neverMounted))
                throw new InvalidOperationException(
                    "Clean disconnected profile must not refresh the desktop");
        }
        var p = new NasProfile { Id = Guid.Parse(
            "13421342-0000-0000-0000-000000000001"),
            Protocol = "TeraBox", Letter = "Q", DisplayName = "Lab" };
        void Verify(bool b, string n)
        {
            if (!b) throw new InvalidOperationException("GHOST_POLICY_FAIL " + n);
        }
        Verify(RemoteMount.IsOurNetworkShare(p, @"\\NASMount-13421342\Lab"),
            "our exact UNC");
        Verify(!RemoteMount.IsOurNetworkShare(p, @"\\RaiDrive-User\Google"),
            "preserve RaiDrive");
        Verify(!RemoteMount.IsOurNetworkShare(p, @"\\NASMount-ffffffff\Lab"),
            "preserve another FileHub AIO profile");
        Verify(ExplorerDriveIcon.IsOwnedHistoryKey("##NASMount-13421342#Lab", p),
            "match our UNC history");
        Verify(!ExplorerDriveIcon.IsOwnedHistoryKey("##NASMount-ffffffff#Lab", p),
            "preserve another profile history");
        Verify(!ExplorerDriveIcon.IsOwnedHistoryKey("##RaiDrive-User#Lab", p),
            "preserve third party history");
        p.Protocol = "SMB";
        Verify(!ExplorerDriveIcon.IsOwnedHistoryKey("##NASMount-13421342#Lab", p),
            "preserve SMB");
        Console.WriteLine("NETWORK_GHOST_OWNER_POLICY_PASS");
    }

    internal static void RegistryIsolationSelfTest()
    {
        // Only disposable keys under random test GUIDs are used.
        var profile = new NasProfile {
            Id = Guid.NewGuid(), Protocol = "GoogleDrive",
            Letter = "Q", DisplayName = "QA-NASMount-ghost"
        };
        var another = new NasProfile {
            Id = Guid.NewGuid(), Protocol = "TeraBox", Letter = "Q"
        };
        const string root = @"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\";
        string owned = root + "##NASMount-" +
            profile.Id.ToString("N")[..8] + "#QA-NASMount-ghost";
        string foreign = root + "##NASMount-" +
            another.Id.ToString("N")[..8] + "#Other";
        try
        {
            using (var key = Registry.CurrentUser.CreateSubKey(owned))
                key?.SetValue("_Icon", "synthetic-qa");
            using (var key = Registry.CurrentUser.CreateSubKey(foreign))
                key?.SetValue("_Icon", "synthetic-qa");
            int removed = ExplorerDriveIcon.ForgetDisconnectedProfileHistory(profile);
            using var kept = Registry.CurrentUser.OpenSubKey(foreign);
            using var gone = Registry.CurrentUser.OpenSubKey(owned);
            if (removed != 1 || kept is null || gone is not null)
                throw new InvalidOperationException(
                    "Only the synthetic owner registry key should be removed.");
            Console.WriteLine("NETWORK_GHOST_REGISTRY_ISOLATION_PASS");
        }
        finally
        {
            Registry.CurrentUser.DeleteSubKeyTree(owned, throwOnMissingSubKey: false);
            Registry.CurrentUser.DeleteSubKeyTree(foreign, throwOnMissingSubKey: false);
        }
    }
}
