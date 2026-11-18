using Microsoft.Win32;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Collections.Concurrent;

namespace NASMount;

/// <summary>
/// Optional per-user Explorer icon for FileHub AIO network drives only. Never
/// overrides a pre-existing icon from another program, and never blocks mount.
/// </summary>
internal static class ExplorerDriveIcon
{
    private const string BaseKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\DriveIcons";
    private const string MountPointsKey = @"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2";
    private const string UserExplorerIcons = @"Software\Classes\Applications\Explorer.exe\Drives";
    private const uint SHCNE_ASSOCCHANGED = 0x08000000;
    private const uint SHCNE_DRIVEREMOVED = 0x00000080;
    private const uint SHCNE_DRIVEADD = 0x00000100;
    private const uint SHCNE_UPDATEITEM = 0x00002000;
    private const uint SHCNE_RMDIR = 0x00000010;
    private const uint SHCNE_NETUNSHARE = 0x00000400;
    private const uint SHCNE_SERVERDISCONNECT = 0x00004000;
    private const uint SHCNE_UPDATEDIR = 0x00001000;
    private const uint SHCNF_IDLIST = 0x0000;
    private const uint SHCNF_PATHW = 0x0005;
    private const int CSIDL_DRIVES = 0x0011;
    private const uint WM_DEVICECHANGE = 0x0219;
    private const uint DBT_DEVICEARRIVAL = 0x8000;
    private const uint DBT_DEVICEREMOVECOMPLETE = 0x8004;
    private const int DBT_DEVTYP_VOLUME = 2;
    private const uint SMTO_ABORTIFHUNG = 0x0002;
    private static readonly IntPtr HwndBroadcast = new(0xffff);
    [StructLayout(LayoutKind.Sequential)]
    private struct DevBroadcastVolume
    {
        public int Size;
        public int DeviceType;
        public int Reserved;
        public int UnitMask;
        public short Flags;
    }
    [DllImport("user32.dll", EntryPoint = "SendMessageTimeoutW", SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint message,
        IntPtr wParam, IntPtr lParam, uint flags, uint timeout, out IntPtr result);
    [DllImport("shell32.dll")]
    private static extern void SHChangeNotify(uint eventId, uint flags, IntPtr first, IntPtr second);
    [DllImport("shell32.dll")]
    private static extern int SHGetSpecialFolderLocation(IntPtr hwnd,
        int folder, out IntPtr pidl);

    internal static string IconPath => Path.Combine(AppContext.BaseDirectory, "tools", "NASMountBadge.ico");
    internal static string Value => IconPath + ",0";

    internal static bool IsOurs(string? raw) =>
        !string.IsNullOrWhiteSpace(raw) &&
        (raw.Contains("NASMountBadge.ico", StringComparison.OrdinalIgnoreCase) ||
         raw.Contains("NASMountNetwork.ico", StringComparison.OrdinalIgnoreCase));

    // Never customize an unrelated local disk. A fixed WinFsp CLOUD volume
    // may receive our icon only when RemoteMount has verified the actual
    // mount and its ownership; a reused ordinary C:/D: disk must be ignored.
    internal static bool MayApplyIcon(DriveType detected,
        bool managedVolumeVerified, bool stableCloudFixed)
        => detected == DriveType.Network ||
           (detected == DriveType.Fixed &&
            stableCloudFixed && managedVolumeVerified);

    // e.g. WinFsp UNC \\NASMount-12345678\Wintec becomes
    // HKCU\...\MountPoints2\##NASMount-12345678#Wintec.
    internal static string MountPointName(NasProfile p)
    {
        string unc = p.Protocol == "SMB" ? p.Unc : "\\" + MountPresentation.WinFspPrefix(p);
        return "##" + unc.TrimStart('\\').Replace('\\', '#');
    }

    internal static string MountPointRegistryPath(NasProfile p) =>
        MountPointsKey + "\\" + MountPointName(p);

    // WinFsp's network drive initially uses a synthetic UNC share. Some
    // providers (MYBOX and OneDrive) create the matching Explorer
    // MountPoints2 entry AFTER the first connected/mounted notification.
    // Apply() alone then misses the label and Explorer renders something
    // like "MY(\\\\NASMount-xxxxxxxx)(U:)" instead of the profile name.
    // Keep the UNC (and network-mode drive) for routing, but override its
    // PRESENTATION only in the exact owned and verified profile namespace.
    internal static bool ShouldShellRename(NasProfile p) =>
        p.Protocol != "SMB";

    // Windows often creates its MountPoints2 UNC entry AFTER WinFsp reports
    // a working root. Keep retrying the label in a dedicated STA worker
    // rather than displaying "Google Drive(\\NASMount-...)" forever.
    private static readonly ConcurrentDictionary<Guid, byte> LabelRepairRunning = new();
    internal static void ScheduleNetworkLabelRepair(NasProfile profile)
    {
        // Google Drive mounts have already been announced once as part of
        // the mount batch. Three consecutive network label repairs produce
        // repeated This PC Shell refreshes, which can block when Google
        // is processing a metadata request. Do not poke Explorer again.
        if (profile.Protocol == "GoogleDrive" ||
            !RemoteMount.UsesNetworkDriveMode(profile) ||
            !RemoteMount.IsMounted(profile) ||
            !LabelRepairRunning.TryAdd(profile.Id, 1)) return;
        var worker = new Thread(() =>
        {
            try
            {
                for (int attempt = 0; attempt < 12; attempt++)
                {
                    if (!RemoteMount.IsMounted(profile)) break;
                    Thread.Sleep(attempt == 0 ? 450 : 1100);
                    if (!RemoteMount.IsMounted(profile)) break;
                    bool fixedName = EnsureConfiguredDisplayName(profile,
                        deferGlobalNotify: true);
                    if (!fixedName) continue;
                    AppStore.Log("NETWORK_LABEL_REPAIRED id=" + profile.Id +
                        " letter=" + profile.Letter + " attempt=" + attempt);
                    NotifyComputerChanged();
                    // A delayed Explorer key may still replace the first
                    // successful rename. Try once more after settle.
                    if (attempt >= 2) break;
                }
            }
            catch (Exception ex)
            {
                AppStore.Log("NETWORK_LABEL_REPAIR_FAILED id=" +
                    profile.Id + " type=" + ex.GetType().Name);
            }
            finally { LabelRepairRunning.TryRemove(profile.Id, out _); }
        })
        { IsBackground = true, Name = "FileHub.NetworkDriveLabel" };
        worker.SetApartmentState(ApartmentState.STA);
        worker.Start();
    }

    internal static bool WriteLabelToExistingKey(RegistryKey? point, string label)
    {
        if (point is null) return false; // Never create phantom network places.
        string? current = point.GetValue("_LabelFromReg") as string;
        if (!string.Equals(current, label, StringComparison.Ordinal))
            point.SetValue("_LabelFromReg", label, RegistryValueKind.String);
        return true;
    }

    internal static bool EnsureConfiguredDisplayName(NasProfile p,
        bool deferGlobalNotify = false)
    {
        if (p.Protocol == "SMB" || p.Letter.Length != 1 ||
            !RemoteMount.UsesNetworkDriveMode(p) ||
            !RemoteMount.HasManagedRecord(p) || !RemoteMount.IsMounted(p) ||
            RemoteMount.HasVerifiedOtherVendorMapping(p))
            return false;
        try
        {
            var drive = new DriveInfo(p.Letter.ToUpperInvariant() + ":\\");
            if (drive.DriveType != DriveType.Network) return false;
            string label = MountPresentation.SafeLabel(p.DisplayName);
            bool applied = false;
            DriveLabelLease.Apply(p);
            // Reuse ONLY the GUID+share-bound MountPoints2 entry generated by
            // the real provider. Its stale _LabelFromReg from an older release
            // is not foreign: overwrite it with the current saved name.
            using (var point = Registry.CurrentUser.OpenSubKey(
                MountPointRegistryPath(p), writable: true))
                applied = WriteLabelToExistingKey(point, label);
            if (ShouldShellRename(p))
            {
                // Network-mounted drives permit the same Shell rename used
                // by working WebDAV/SFTP connections. Previously this was
                // disabled for MYBOX and OneDrive, exposing a UNC suffix.
                // Guard is above: never rename foreign or non-managed drives.
                try
                {
                    Type? shellType = Type.GetTypeFromProgID("Shell.Application");
                    dynamic? shell = shellType is null ? null :
                        Activator.CreateInstance(shellType);
                    dynamic? folder = shell?.NameSpace(p.Letter.ToUpperInvariant() + ":");
                    if (folder is not null)
                    {
                        dynamic item = folder.Self;
                        string? present = (string?)item.Name;
                        if (!string.Equals(present, label, StringComparison.Ordinal))
                            item.Name = label;
                        applied = true;
                    }
                }
                catch (Exception ex)
                {
                    AppStore.Log("NETWORK_DISPLAY_NAME_SHELL_SKIP " +
                        p.Id + " " + ex.GetType().Name);
                }
            }
            if (applied)
            {
                if (!deferGlobalNotify)
                {
                    SHChangeNotify(SHCNE_ASSOCCHANGED, 0, IntPtr.Zero, IntPtr.Zero);
                    NotifyComputerChanged();
                }
            }
            else
                AppStore.Log("NETWORK_DISPLAY_NAME_DELAYED id=" + p.Id +
                    " letter=" + p.Letter + " (MountPoints2 not registered yet)");
            return applied;
        }
        catch (Exception ex)
        {
            AppStore.Log("NETWORK_DISPLAY_NAME_WARNING " +
                p.Id + " " + ex.GetType().Name);
            return false;
        }
    }

    internal static void DisplayNamePolicyTest()
    {
        static void Assert(bool condition, string detail)
        {
            if (!condition)
                throw new InvalidOperationException("EXPLORER_DISPLAY_LABEL_FAIL " + detail);
        }
        var mybox = new NasProfile {
            Id = Guid.Parse("11112222-3333-4444-5555-666677778888"),
            Protocol = "MyBox", DisplayName = "네이버", Letter = "U"
        };
        var oneDrive = new NasProfile {
            Id = Guid.Parse("aaaa2222-3333-4444-5555-666677778888"),
            Protocol = "OneDrive", DisplayName = "회사 OneDrive", Letter = "O"
        };
        Assert(ShouldShellRename(mybox), "MYBOX shell rename must run");
        Assert(ShouldShellRename(oneDrive), "OneDrive shell rename must run");
        Assert(ShouldShellRename(new NasProfile { Protocol = "GoogleDrive" }) &&
            ShouldShellRename(new NasProfile { Protocol = "TeraBox" }),
            "Google and TeraBox must hide raw NASMount UNC suffix");
        Assert(MountPointName(mybox) == "##NASMount-11112222#네이버",
            "MYBOX exact owned share");
        Assert(MountPointName(oneDrive) ==
            "##NASMount-aaaa2222#회사 OneDrive", "OneDrive exact owned share");
        Assert(!WriteLabelToExistingKey(null, "네이버"),
            "never synthesize phantom MountPoints2 entry");
        string qaRoot = @"Software\NASMount\ExplorerNameQa\" +
            Guid.NewGuid().ToString("N");
        try
        {
            using (var key = Registry.CurrentUser.CreateSubKey(qaRoot))
            {
                if (key is null) throw new IOException("Unable to create QA registry key");
                key.SetValue("_LabelFromReg", @"MY(\\NASMount-fee5edf8)");
                Assert(WriteLabelToExistingKey(key, "네이버"),
                    "replace legacy MYBOX registry label");
                Assert((string?)key.GetValue("_LabelFromReg") == "네이버",
                    "MYBOX saved name wins over stale label");
                Assert(WriteLabelToExistingKey(key, "네이버"),
                    "same label is idempotent");
                Assert(WriteLabelToExistingKey(key, "회사 OneDrive") &&
                    (string?)key.GetValue("_LabelFromReg") == "회사 OneDrive",
                    "OneDrive label uses its saved name");
            }
        }
        finally
        {
            Registry.CurrentUser.DeleteSubKeyTree(qaRoot,
                throwOnMissingSubKey: false);
        }
        Console.WriteLine("EXPLORER_CLOUD_LABEL_MYBOX_ONEDRIVE_POLICY_PASS");
    }

    private static void LogPossibleIconCollision(NasProfile p)
    {
        try
        {
            string letter = p.Letter.ToUpperInvariant();
            static string? Read(RegistryKey hive, string key, string name)
            {
                using var item = hive.OpenSubKey(key);
                return item?.GetValue(name) as string;
            }
            static string Kind(string? value) =>
                string.IsNullOrWhiteSpace(value) ? "none" :
                IsOurs(value) ? "NASMount" :
                value.Contains("RaiDrive", StringComparison.OrdinalIgnoreCase) ?
                    "RaiDrive" : "other";
            string user = Kind(Read(Registry.CurrentUser,
                BaseKey + "\\" + letter + "\\DefaultIcon", ""));
            string classes = Kind(Read(Registry.CurrentUser,
                UserExplorerIcons + "\\" + letter + "\\DefaultIcon", ""));
            // HKLM is read-only: never overwrite icons installed by RaiDrive
            // or any other product for the whole computer.
            string machine = Kind(Read(Registry.LocalMachine,
                BaseKey + "\\" + letter + "\\DefaultIcon", ""));
            string unc = Kind(Read(Registry.CurrentUser,
                MountPointRegistryPath(p), "_Icon"));
            AppStore.Log("ICON_SOURCE_BEFORE_OVERRIDE id=" + p.Id +
                " letter=" + letter + " HKCU_DriveIcons=" + user +
                " HKCU_ExplorerClasses=" + classes +
                " HKLM_DriveIcons=" + machine +
                " NASMOUNT_UNC_MountPoints2=" + unc);
        }
        catch (Exception ex)
        {
            AppStore.Log("ICON_SOURCE_READONLY_WARNING id=" + p.Id +
                " " + ex.Message);
        }
    }

    internal static bool Apply(NasProfile p, bool deferGlobalNotify = false)
    {
        if (!File.Exists(IconPath) || p.Letter.Length != 1) return false;
        try
        {
            // Do not touch an ordinary PC disk or another vendor's drive.
            if (!RemoteMount.HasManagedRecord(p) || !RemoteMount.IsMounted(p) ||
                RemoteMount.HasVerifiedOtherVendorMapping(p)) return false;
            var drive = new DriveInfo(p.Letter.ToUpperInvariant() + ":\\");
            bool cloudFixed = RemoteMount.UsesStableCloudFixedMode(p);
            if (!MayApplyIcon(drive.DriveType,
                RemoteMount.IsMounted(p), cloudFixed)) return false;
            DriveLabelLease.Apply(p);
            string path = BaseKey + "\\" + p.Letter.ToUpperInvariant() + "\\DefaultIcon";
            LogPossibleIconCollision(p);
            // Old implementation returned false when RaiDrive (or another
            // product) left its icon in this drive-letter slot. For an
            // actively VERIFIED NASMount volume, borrow only the *icon
            // value* and remember the previous per-user value to restore
            // after safe disconnect. Never change that application's keys.
            bool applied = DriveIconLease.Acquire(p, "ExplorerDriveIcons",
                path, "", Value);
            // Explorer uses different registration for network drives:
            // its label is read from the UNC-specific MountPoints2 entry,
            // not simply from DriveIcons/<letter>/DefaultLabel.
            // Fixed mode must never register a synthetic
            // ##NASMount-GUID8#Google Drive network location in MountPoints2.
            if (!cloudFixed)
            using (var point = Registry.CurrentUser.OpenSubKey(
                MountPointRegistryPath(p), writable: true))
            {
                if (point is not null)
                {
                    string label = MountPresentation.SafeLabel(p.DisplayName);
                    WriteLabelToExistingKey(point, label);
                    applied |= DriveIconLease.Acquire(p, "OwnUncMountPoint",
                        MountPointRegistryPath(p), "_Icon", IconPath, create: false);
                }
                // DO NOT create a synthetic MountPoints2 entry just to set
                // an icon: it can become a persistent red-X network place.
            }
            applied |= DriveIconLease.Acquire(p, "ExplorerClasses",
                UserExplorerIcons + "\\" + p.Letter.ToUpperInvariant() + "\\DefaultIcon",
                "", Value);
            // A WinFsp network provider can block Shell.Application rename
            // for tens of seconds. Use the single background STA worker,
            // never the WinForms UI thread.
            if (!cloudFixed)
                ScheduleNetworkLabelRepair(p);
            if (applied && !deferGlobalNotify)
            {
                SHChangeNotify(SHCNE_ASSOCCHANGED, 0, IntPtr.Zero, IntPtr.Zero);
                NotifyComputerChanged();
            }
            return applied;
        }
        catch (Exception ex)
        {
            AppStore.Log("NETWORK_ICON_APPLY_SKIP " + p.Id + " " + ex.Message);
            return false;
        }
    }

    internal static void Restore(NasProfile p)
    {
        if (p.Letter.Length != 1) return;
        try
        {
            DriveLabelLease.Restore(p);
            string path = BaseKey + "\\" + p.Letter.ToUpperInvariant() + "\\DefaultIcon";
            DriveIconLease.Release(p, "ExplorerDriveIcons", path, "");
            using (var point = Registry.CurrentUser.OpenSubKey(MountPointRegistryPath(p), writable: true))
            {
                if (string.Equals(point?.GetValue("_LabelFromReg") as string,
                    MountPresentation.SafeLabel(p.DisplayName), StringComparison.Ordinal))
                    point?.DeleteValue("_LabelFromReg", throwOnMissingValue: false);
            }
            DriveIconLease.Release(p, "OwnUncMountPoint",
                MountPointRegistryPath(p), "_Icon");
            DriveIconLease.Release(p, "ExplorerClasses",
                UserExplorerIcons + "\\" + p.Letter.ToUpperInvariant() + "\\DefaultIcon",
                "");
            SHChangeNotify(SHCNE_ASSOCCHANGED, 0, IntPtr.Zero, IntPtr.Zero);
            if (!RemoteMount.DriveLetterExists(p.Letter))
            {
                IntPtr letterPath = Marshal.StringToHGlobalUni(
                    p.Letter.ToUpperInvariant() + ":\\");
                try
                {
                    // Ask Explorer to forget its cached network-drive entry.
                    // Does not unmount a filesystem or affect remote files.
                    SHChangeNotify(SHCNE_DRIVEREMOVED, SHCNF_PATHW,
                        letterPath, IntPtr.Zero);
                }
                finally { Marshal.FreeHGlobal(letterPath); }
            }
        }
        catch (Exception ex) { AppStore.Log("NETWORK_ICON_RESTORE_SKIP " + p.Id + " " + ex.Message); }
    }

    // Removing _Icon is NOT enough: Explorer retains disconnected FUSE-rclone
    // shares in MountPoints2. Forget only this exact profile's history AFTER
    // its rclone/WinFsp process has safely stopped.
    internal static int ForgetDisconnectedProfileHistory(NasProfile profile)
    {
        if (profile.Protocol == "SMB") return 0;
        int removed = 0;
        try
        {
            using var history = Registry.CurrentUser.OpenSubKey(MountPointsKey,
                writable: true);
            if (history is null) return 0;
            foreach (string key in history.GetSubKeyNames())
            {
                if (!IsOwnedHistoryKey(key, profile)) continue;
                history.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
                removed++;
                AppStore.Log("NETWORK_MOUNTPOINT_HISTORY_FORGOTTEN " +
                    profile.Id + " " + key);
                // Removing MountPoints2 alone does not clear Explorer's
                // cached red-X UNC. Retire this exact owned server/share.
                string retired = @"\\" + key[2..].Replace('#', '\\');
                NotifyRetiredOwnedShare(profile, retired);
            }
        }
        catch (Exception ex)
        {
            AppStore.Log("NETWORK_MOUNTPOINT_HISTORY_CLEANUP_FAILED " +
                profile.Id + " " + ex.Message);
            return -1;
        }
        if (removed > 0) NotifyDisconnected(profile);
        return removed;
    }

    private static void NotifyRetiredOwnedShare(NasProfile profile, string unc)
    {
        if (!RemoteMount.IsOurNetworkShare(profile, unc)) return;
        int endServer = unc.IndexOf('\\', 2);
        if (endServer <= 2) return;
        IntPtr share = Marshal.StringToHGlobalUni(unc);
        IntPtr server = Marshal.StringToHGlobalUni(unc[..endServer]);
        try
        {
            SHChangeNotify(SHCNE_NETUNSHARE, SHCNF_PATHW, share, IntPtr.Zero);
            SHChangeNotify(SHCNE_RMDIR, SHCNF_PATHW, share, IntPtr.Zero);
            SHChangeNotify(SHCNE_SERVERDISCONNECT, SHCNF_PATHW,
                server, IntPtr.Zero);
        }
        finally
        {
            Marshal.FreeHGlobal(share);
            Marshal.FreeHGlobal(server);
        }
    }

    internal static bool IsOwnedHistoryKey(string key, NasProfile profile) =>
        profile.Protocol != "SMB" &&
        Regex.IsMatch(key, @"\A##NASMount-" +
            profile.Id.ToString("N")[..8] + @"(?:-[a-fA-F0-9]{8})?#",
            RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);

    // One-time migration for profiles deleted in old releases: no saved GUID
    // remains, but Explorer still remembers \\NASMount-xxxxxxxx\old-label.
    // Only shell-history entries from FileHub AIO are touched. The caller must
    // supply GUID prefixes of ALL live mapped drives so these are retained.
    internal static int PruneOldUnmountedHistory(IReadOnlySet<string> activeGuids)
    {
        int count = 0;
        try
        {
            using var history = Registry.CurrentUser.OpenSubKey(MountPointsKey,
                writable: true);
            if (history is null) return 0;
            foreach (string key in history.GetSubKeyNames())
            {
                Match match = Regex.Match(key,
                    @"^##NASMount-([0-9A-Fa-f]{8})(?:-[0-9A-Fa-f]{8})?#",
                    RegexOptions.CultureInvariant | RegexOptions.IgnoreCase);
                if (!match.Success) continue;
                if (activeGuids.Contains(match.Groups[1].Value)) continue;
                history.DeleteSubKeyTree(key, throwOnMissingSubKey: false);
                count++;
                AppStore.Log("ORPHAN_NASMOUNT_HISTORY_PRUNED " + key);
            }
            if (count > 0) SHChangeNotify(SHCNE_ASSOCCHANGED, 0,
                IntPtr.Zero, IntPtr.Zero);
        }
        catch (Exception ex)
        {
            AppStore.Log("ORPHAN_NASMOUNT_HISTORY_PRUNE_SKIPPED " + ex.Message);
            return -1;
        }
        return count;
    }

    // Read the actual Windows "This PC" Shell namespace rather than only
    // registry records or GetLogicalDrives. Explorer can cache red-X network
    // locations after the WinFsp process is gone.
    internal static bool? ComputerFolderStillShows(string letter)
    {
        if (letter.Length != 1) return null;
        try
        {
            Type? shellType = Type.GetTypeFromProgID("Shell.Application");
            if (shellType is null) return null;
            dynamic? shell = Activator.CreateInstance(shellType);
            dynamic? computer = shell?.NameSpace(17); // CSIDL_DRIVES
            if (computer is null) return null;
            foreach (dynamic item in computer.Items())
            {
                string path = (string?)item.Path ?? "";
                if (string.Equals(path.TrimEnd('\\'),
                    letter.ToUpperInvariant() + ":",
                    StringComparison.OrdinalIgnoreCase))
                    return true;
            }
            return false;
        }
        catch (Exception ex)
        {
            AppStore.Log("COMPUTER_SHELL_STATUS_WARNING " +
                letter + " " + ex.Message);
            return null;
        }
    }

    // Explorer COM can block independently of the WinFsp mount. Never
    // execute this lookup on the WinForms UI thread or start repeated
    // COM workers while an earlier query is still running.
    private static readonly ConcurrentDictionary<string, Task<bool?>> ShellLookups =
        new(StringComparer.OrdinalIgnoreCase);
    private static Task<bool?> StartShellStaLookup(string driveLetter)
    {
        var completion = new TaskCompletionSource<bool?>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { completion.TrySetResult(ComputerFolderStillShows(driveLetter)); }
            catch (Exception ex)
            {
                AppStore.Log("EXPLORER_STA_LOOKUP_FAIL " + ex.GetType().Name);
                completion.TrySetResult(null);
            }
        })
        {
            IsBackground = true,
            Name = "FileHub.ExplorerVisibility." + driveLetter
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }
    internal static async Task<bool?> ComputerFolderShowsAsync(string letter)
    {
        if (letter.Length != 1 || !char.IsLetter(letter[0])) return null;
        string normalized = letter.ToUpperInvariant();
        Task<bool?> check = ShellLookups.GetOrAdd(normalized, StartShellStaLookup);
        try { return await check.WaitAsync(TimeSpan.FromSeconds(4)); }
        catch (TimeoutException)
        {
            AppStore.Log("EXPLORER_SHELL_PROBE_TIMEOUT letter=" + letter);
            return null;
        }
        finally
        {
            if (check.IsCompleted)
                ((ICollection<KeyValuePair<string, Task<bool?>>>)ShellLookups)
                    .Remove(new(normalized, check));
        }
    }

    internal static bool HasOwnedPresentationResidue(NasProfile p)
    {
        if (p.Protocol == "SMB" || p.Letter.Length != 1) return false;
        try
        {
            if (DriveIconLease.HasRecordedLease(p)) return true;
            // Only exact profile GUID UNC metadata; never match another
            // app's registry record or an unrelated reused letter.
            using var owned = Registry.CurrentUser.OpenSubKey(
                MountPointRegistryPath(p));
            return owned is not null;
        }
        catch (Exception ex)
        {
            AppStore.Log("SHELL_RESIDUE_PROBE_NO_CLEANUP " +
                p.Id + " " + ex.GetType().Name);
            return false;
        }
    }

    internal static bool HasOwnedMountPointHistory(NasProfile profile)
    {
        if (profile.Protocol == "SMB") return false;
        try
        {
            using var history = Registry.CurrentUser.OpenSubKey(MountPointsKey);
            return history?.GetSubKeyNames().Any(key =>
                IsOwnedHistoryKey(key, profile)) == true;
        }
        catch { return false; }
    }

    internal static void NotifyComputerChanged()
    {
        SHChangeNotify(SHCNE_ASSOCCHANGED, 0, IntPtr.Zero, IntPtr.Zero);
        if (SHGetSpecialFolderLocation(IntPtr.Zero, CSIDL_DRIVES,
                out IntPtr computerPidl) == 0 && computerPidl != IntPtr.Zero)
        {
            try
            {
                SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_IDLIST,
                    computerPidl, IntPtr.Zero);
            }
            finally { Marshal.FreeCoTaskMem(computerPidl); }
        }
    }

    /// <summary>
    /// Notify Windows Shell that an exact OLD NASMount network server is
    /// gone, after the caller verified no live WinFsp, rclone or network
    /// provider still owns it. Does not delete files, force-unmount, or
    /// touch a historical drive letter that may now be reused.
    /// </summary>
    internal static void NotifyRetiredNetworkServer(
        string guid8, IEnumerable<string> verifiedOldShares)
    {
        if (!Regex.IsMatch(guid8, @"\A[a-f0-9]{8}\z",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
            throw new ArgumentException("Only a NASMount 8-digit ID is allowed");
        string server = @"\\NASMount-" + guid8;
        int shareEvents = 0;
        foreach (string share in verifiedOldShares
            .Distinct(StringComparer.OrdinalIgnoreCase).Take(20))
        {
            if (!share.StartsWith(server + @"\", StringComparison.OrdinalIgnoreCase) ||
                share.Contains("..", StringComparison.Ordinal) ||
                share.Length > 250) continue;
            IntPtr path = Marshal.StringToHGlobalUni(share);
            try
            {
                SHChangeNotify(SHCNE_NETUNSHARE, SHCNF_PATHW, path, IntPtr.Zero);
                SHChangeNotify(SHCNE_RMDIR, SHCNF_PATHW, path, IntPtr.Zero);
                shareEvents++;
            }
            finally { Marshal.FreeHGlobal(path); }
        }
        IntPtr name = Marshal.StringToHGlobalUni(server);
        try
        {
            SHChangeNotify(SHCNE_SERVERDISCONNECT, SHCNF_PATHW, name, IntPtr.Zero);
            NotifyComputerChanged();
        }
        finally { Marshal.FreeHGlobal(name); }
        AppStore.Log("SHELL_RETIRED_SERVER_DISCONNECT_SENT id=" + guid8 +
            " verifiedShareEvents=" + shareEvents +
            " driveLetterEvents=0");
    }

    /// <summary>
    /// Send device arrival/removal messages ONLY after the backend has
    /// verified the WinFsp mount or a safe disconnect. No Explorer process
    /// restart or driver unload is involved.
    /// </summary>
    internal static bool NotifyMounted(NasProfile profile,
        bool deferGlobalNotify = false)
    {
        if (profile.Protocol == "SMB" || profile.Letter.Length != 1 ||
            !RemoteMount.IsMounted(profile))
            return false;
        IntPtr drive = Marshal.StringToHGlobalUni(
            profile.Letter.ToUpperInvariant() + ":\\");
        try
        {
            SHChangeNotify(SHCNE_DRIVEADD, SHCNF_PATHW, drive, IntPtr.Zero);
            SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW, drive, IntPtr.Zero);
            BroadcastVolumeChange(profile.Letter, arrival: true);
            if (!deferGlobalNotify) NotifyComputerChanged();
            AppStore.Log("SHELL_DRIVE_ARRIVAL_SENT profile=" + profile.Id +
                " letter=" + profile.Letter +
                " mode=" + (RemoteMount.UsesNetworkDriveMode(profile)
                    ? "network-unc" : "fixed-disk"));
            return true;
        }
        finally { Marshal.FreeHGlobal(drive); }
    }

    /// <summary>
    /// Broadcast one WM_DEVICECHANGE with the combined volume mask of
    /// verified FileHub AIO drives, followed by one computer-folder refresh.
    /// Real WinFsp mounts remain independent; only Shell presentation waits.
    /// </summary>
    internal static int NotifyMountedBatch(IEnumerable<NasProfile> profiles)
    {
        var ready = profiles.Where(p =>
                p.Protocol != "SMB" && p.Letter.Length == 1 &&
                RemoteMount.IsMounted(p) &&
                !RemoteMount.HasVerifiedOtherVendorMapping(p))
            .GroupBy(p => p.Letter, StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First()).ToArray();
        if (ready.Length == 0) return 0;
        int mask = BatchVolumeUnitMask(ready.Select(p => p.Letter));
        BroadcastVolumeChange(mask, arrival: true, "batch");
        NotifyComputerChanged();
        AppStore.Log("SHELL_BATCH_ARRIVAL_SENT drives=" + ready.Length +
            " mask=" + mask + " oneVolumeBroadcast=true oneComputerRefresh=true");
        return ready.Length;
    }

    internal static int BatchVolumeUnitMask(IEnumerable<string> letters)
    {
        int mask = 0;
        foreach (string letter in letters)
            mask |= VolumeUnitMask(letter);
        return mask;
    }

    internal static bool NotifySafeUnmounted(NasProfile profile)
    {
        if (profile.Protocol == "SMB" || profile.Letter.Length != 1 ||
            RemoteMount.IsMounted(profile) ||
            RemoteMount.DriveLetterExists(profile.Letter))
        {
            AppStore.Log("SHELL_DRIVE_REMOVE_SKIPPED_NOT_RELEASED " +
                profile.Id + " letter=" + profile.Letter);
            return false;
        }
        NotifyDisconnected(profile);
        BroadcastVolumeChange(profile.Letter, arrival: false);
        NotifyComputerChanged();
        AppStore.Log("SHELL_DRIVE_REMOVAL_SENT profile=" + profile.Id +
            " letter=" + profile.Letter);
        return true;
    }

    internal static int VolumeUnitMask(string letter)
    {
        if (letter.Length != 1 || !char.IsAsciiLetter(letter[0]))
            throw new ArgumentException("Drive letter must be A-Z.", nameof(letter));
        return 1 << (char.ToUpperInvariant(letter[0]) - 'A');
    }

    private static void BroadcastVolumeChange(string letter, bool arrival) =>
        BroadcastVolumeChange(VolumeUnitMask(letter), arrival, letter);

    private static void BroadcastVolumeChange(int unitMask, bool arrival, string label)
    {
        var message = new DevBroadcastVolume {
            Size = Marshal.SizeOf<DevBroadcastVolume>(),
            DeviceType = DBT_DEVTYP_VOLUME,
            UnitMask = unitMask
        };
        IntPtr buffer = Marshal.AllocHGlobal(message.Size);
        try
        {
            Marshal.StructureToPtr(message, buffer, false);
            IntPtr sent = SendMessageTimeout(HwndBroadcast,
                WM_DEVICECHANGE,
                new IntPtr(arrival ? DBT_DEVICEARRIVAL : DBT_DEVICEREMOVECOMPLETE),
                buffer, SMTO_ABORTIFHUNG, 400, out _);
            AppStore.Log("SHELL_WM_DEVICECHANGE " +
                (arrival ? "ARRIVAL" : "REMOVE") +
                " letter=" + label + " unitMask=" + unitMask +
                " dispatched=" + (sent != IntPtr.Zero));
        }
        catch (Exception ex)
        {
            AppStore.Log("SHELL_WM_DEVICECHANGE_WARNING " + ex.Message);
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    // Gated integration test of precisely the same mount notification path.
    // Never mount/unmount here; test Q: is managed separately via rclone RC.
    internal static void TestOnlyNotifyQ(bool arrival)
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_Q_SHELL_TEST") != "1")
            throw new InvalidOperationException("Shell Q: test is not enabled");
        bool ready = new DriveInfo("Q:\\").DriveType == DriveType.Network;
        if (arrival != ready)
            throw new InvalidOperationException(
                "Test volume state mismatch; refusing Shell notification");
        IntPtr path = Marshal.StringToHGlobalUni("Q:\\");
        try
        {
            SHChangeNotify(arrival ? SHCNE_DRIVEADD : SHCNE_DRIVEREMOVED,
                SHCNF_PATHW, path, IntPtr.Zero);
            BroadcastVolumeChange("Q", arrival);
            NotifyComputerChanged();
            ExplorerLiveViewRefresh.RefreshThisPc(
                arrival ? "Q_TEST_MOUNTED" : "Q_TEST_UNMOUNTED");
            Console.WriteLine(arrival ?
                "WINFSP_Q_SHELL_DRIVE_ARRIVAL_PASS" :
                "WINFSP_Q_SHELL_DRIVE_REMOVAL_PASS");
        }
        finally { Marshal.FreeHGlobal(path); }
    }

    internal static void NotifyDisconnected(NasProfile profile)
    {
        if (profile.Protocol == "SMB" || profile.Letter.Length != 1) return;
        IntPtr drive = Marshal.StringToHGlobalUni(
            profile.Letter.ToUpperInvariant() + ":\\");
        IntPtr unc = Marshal.StringToHGlobalUni(
            "\\" + MountPresentation.WinFspPrefix(profile));
        IntPtr server = Marshal.StringToHGlobalUni(
            @"\\" + MountPresentation.NetworkServerName(profile));
        try
        {
            SHChangeNotify(SHCNE_DRIVEREMOVED, SHCNF_PATHW, drive, IntPtr.Zero);
            if (!RemoteMount.UsesStableCloudFixedMode(profile))
            {
                SHChangeNotify(SHCNE_NETUNSHARE, SHCNF_PATHW, unc, IntPtr.Zero);
                // Network-mode NAS connections have a real WinFsp UNC
                // server. Fixed-mode cloud drives do not: sending synthetic
                // UNC events for them can resurrect old Explorer place IDs.
                SHChangeNotify(SHCNE_RMDIR, SHCNF_PATHW, unc, IntPtr.Zero);
                SHChangeNotify(SHCNE_SERVERDISCONNECT, SHCNF_PATHW,
                    server, IntPtr.Zero);
            }
            SHChangeNotify(SHCNE_ASSOCCHANGED, 0, IntPtr.Zero, IntPtr.Zero);
            // A live Explorer "This PC" window is a virtual Shell folder.
            // Invalidate its PIDL explicitly, not just the old drive path.
            if (SHGetSpecialFolderLocation(IntPtr.Zero, CSIDL_DRIVES,
                    out IntPtr computerPidl) == 0 &&
                computerPidl != IntPtr.Zero)
            {
                try
                {
                    SHChangeNotify(SHCNE_UPDATEDIR, SHCNF_IDLIST,
                        computerPidl, IntPtr.Zero);
                }
                finally { Marshal.FreeCoTaskMem(computerPidl); }
            }
        }
        finally
        {
            Marshal.FreeHGlobal(drive);
            Marshal.FreeHGlobal(unc);
            Marshal.FreeHGlobal(server);
        }
    }
}
