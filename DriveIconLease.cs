using Microsoft.Win32;

namespace NASMount;

/// <summary>
/// Per-profile, per-letter reversible icon override for FileHub AIO-owned
/// volumes. Another installed drive application may leave an icon for Z:
/// in the CURRENT USER's registry even when its executable is not running.
/// Preserve that original value and restore it on safe unmount; never edit
/// RaiDrive/third-party application settings or HKLM DriveIcons.
/// </summary>
internal static class DriveIconLease
{
    private const string LeaseRoot = @"Software\NASMount\IconLeases";
    private static string LeaseId(NasProfile p, string scope) =>
        p.Id.ToString("N") + "_" + p.Letter.ToUpperInvariant() + "_" + scope;

    // Read-only. Avoid redundant icon restore / shell invalidation on every
    // healthy, disconnected profile during automatic startup.
    internal static bool HasRecordedLease(NasProfile profile)
    {
        if (profile.Letter.Length != 1) return false;
        using var leases = Registry.CurrentUser.OpenSubKey(LeaseRoot);
        if (leases is null) return false;
        string prefix = profile.Id.ToString("N") + "_" +
            profile.Letter.ToUpperInvariant() + "_";
        return leases.GetSubKeyNames().Any(name =>
            name.StartsWith(prefix, StringComparison.OrdinalIgnoreCase));
    }

    internal static bool CanClaim(bool managed, bool mounted, bool foreignOwner) =>
        managed && mounted && !foreignOwner;

    internal static bool CanRestore(string? observedIcon, bool hasOwnershipLease) =>
        hasOwnershipLease && ExplorerDriveIcon.IsOurs(observedIcon);

    internal static bool Acquire(NasProfile profile, string scope,
        string targetKey, string name, string icon, bool create = true)
    {
        if (profile.Letter.Length != 1 ||
            !CanClaim(RemoteMount.HasManagedRecord(profile),
                RemoteMount.IsMounted(profile),
                RemoteMount.HasVerifiedOtherVendorMapping(profile)))
            return false;
        return AcquireVerified(profile, scope, targetKey, name, icon, create);
    }

    // Acquisition from a real mount must pass the ownership gate above.
    // Only isolated internal QA calls this with an app-owned test key.
    private static bool AcquireVerified(NasProfile profile, string scope,
        string targetKey, string name, string icon, bool create = true)
    {
        try
        {
            using var previous = Registry.CurrentUser.OpenSubKey(targetKey);
            if (previous is null && !create) return false;
            string? prior = previous?.GetValue(name, null,
                RegistryValueOptions.DoNotExpandEnvironmentNames) as string;
            // Back up ONLY our exact owned profile + letter. An existing
            // recorded lease survives an app crash and must not be replaced
            // with the temporary NASMount icon at the next launch.
            string leasePath = LeaseRoot + "\\" + LeaseId(profile, scope);
            using (var lease = Registry.CurrentUser.CreateSubKey(leasePath, true))
            {
                if (lease is null) return false;
                if (lease.GetValue("Ready") is not int ready || ready != 1)
                {
                    bool hadPrior = !string.IsNullOrEmpty(prior) &&
                        !ExplorerDriveIcon.IsOurs(prior);
                    lease.SetValue("Letter", profile.Letter.ToUpperInvariant());
                    lease.SetValue("Key", targetKey);
                    lease.SetValue("ValueName", name);
                    lease.SetValue("HadPrior", hadPrior ? 1 : 0, RegistryValueKind.DWord);
                    lease.SetValue("Prior", hadPrior ? prior! : "",
                        RegistryValueKind.String);
                    RegistryValueKind kind = RegistryValueKind.String;
                    if (hadPrior)
                    {
                        try { kind = previous!.GetValueKind(name); }
                        catch (IOException) { }
                    }
                    lease.SetValue("PriorKind", (int)kind, RegistryValueKind.DWord);
                    lease.SetValue("Ready", 1, RegistryValueKind.DWord);
                    AppStore.Log("OWNED_ICON_LEASE_SAVED id=" + profile.Id +
                        " letter=" + profile.Letter + " scope=" + scope +
                        " displacedForeign=" + hadPrior);
                }
            }
            using var target = Registry.CurrentUser.CreateSubKey(targetKey, true);
            if (target is null) return false;
            target.SetValue(name, icon, RegistryValueKind.String);
            AppStore.Log("OWNED_ICON_APPLIED id=" + profile.Id + " letter=" +
                profile.Letter + " scope=" + scope);
            return true;
        }
        catch (Exception ex)
        {
            AppStore.Log("OWNED_ICON_APPLY_WARNING id=" + profile.Id +
                " scope=" + scope + " " + ex.Message);
            return false;
        }
    }

    internal static void Release(NasProfile profile, string scope,
        string targetKey, string name)
    {
        if (profile.Letter.Length != 1) return;
        try
        {
            string leasePath = LeaseRoot + "\\" + LeaseId(profile, scope);
            using var lease = Registry.CurrentUser.OpenSubKey(leasePath);
            bool valid = lease?.GetValue("Ready") is int ready && ready == 1 &&
                string.Equals(lease.GetValue("Key") as string, targetKey,
                    StringComparison.OrdinalIgnoreCase) &&
                string.Equals(lease.GetValue("ValueName") as string, name,
                    StringComparison.Ordinal) &&
                string.Equals(lease.GetValue("Letter") as string,
                    profile.Letter, StringComparison.OrdinalIgnoreCase);
            using var target = Registry.CurrentUser.OpenSubKey(targetKey, writable: true);
            string? current = target?.GetValue(name) as string;
            if (!CanRestore(current, valid) && !ExplorerDriveIcon.IsOurs(current))
            {
                // Somebody else updated its own icon while NASMount was
                // running. Do NOT overwrite their more recent value.
                AppStore.Log("OWNED_ICON_RESTORE_FOREIGN_PRESERVED id=" +
                    profile.Id + " scope=" + scope);
            }
            else if (ExplorerDriveIcon.IsOurs(current))
            {
                bool hadPrior = valid && lease?.GetValue("HadPrior") is int had &&
                    had == 1 && lease.GetValue("Prior") is string;
                if (hadPrior)
                {
                    string old = (string)lease!.GetValue("Prior")!;
                    var kind = (RegistryValueKind)Convert.ToInt32(
                        lease.GetValue("PriorKind") ?? (int)RegistryValueKind.String);
                    if (kind is not (RegistryValueKind.String or RegistryValueKind.ExpandString))
                        kind = RegistryValueKind.String;
                    target?.SetValue(name, old, kind);
                }
                else
                    target?.DeleteValue(name, throwOnMissingValue: false);
                AppStore.Log("OWNED_ICON_RESTORED id=" + profile.Id +
                    " scope=" + scope + " restoredPrior=" + hadPrior);
            }
            lease?.Dispose();
            if (valid)
                Registry.CurrentUser.DeleteSubKeyTree(leasePath,
                    throwOnMissingSubKey: false);
        }
        catch (Exception ex)
        {
            AppStore.Log("OWNED_ICON_RESTORE_WARNING id=" + profile.Id +
                " scope=" + scope + " " + ex.Message);
        }
    }

    internal static void PolicyTest()
    {
        if (!CanClaim(true, true, false) ||
            CanClaim(false, true, false) ||
            CanClaim(true, false, false) ||
            CanClaim(true, true, true) ||
            CanRestore(@"C:\RaiDrive\Drive.ico,0", true) ||
            CanRestore(@"C:\NASMount\NASMountBadge.ico,0", false) ||
            !CanRestore(@"C:\NASMount\NASMountBadge.ico,0", true))
            throw new InvalidOperationException("Drive icon lease safety policy failed");
        var fake = new NasProfile { Id = Guid.NewGuid(), Letter = "Q" };
        string testRoot = @"Software\NASMount\IconLeaseQa\" + fake.Id.ToString("N");
        string targetPath = testRoot + @"\DefaultIcon";
        const string prior = @"C:\Program Files\OpenBoxLab\RaiDrive\icon.ico,0";
        const string ours = @"C:\NASMount\tools\NASMountBadge.ico,0";
        try
        {
            using (var target = Registry.CurrentUser.CreateSubKey(targetPath))
                target?.SetValue("", prior, RegistryValueKind.String);
            if (!AcquireVerified(fake, "QaOriginal", targetPath, "", ours))
                throw new InvalidOperationException("Test icon lease acquisition failed");
            using (var target = Registry.CurrentUser.OpenSubKey(targetPath))
                if ((string?)target?.GetValue("") != ours)
                    throw new InvalidOperationException("Temporary icon not written");
            Release(fake, "QaOriginal", targetPath, "");
            using (var target = Registry.CurrentUser.OpenSubKey(targetPath))
                if ((string?)target?.GetValue("") != prior)
                    throw new InvalidOperationException("Original icon not restored");
            // Do not overwrite a more recent icon from another application.
            if (!AcquireVerified(fake, "QaForeignWins", targetPath, "", ours))
                throw new InvalidOperationException("Second icon lease failed");
            using (var target = Registry.CurrentUser.OpenSubKey(targetPath, true))
                target?.SetValue("", @"C:\Other\new.ico,0");
            Release(fake, "QaForeignWins", targetPath, "");
            using (var target = Registry.CurrentUser.OpenSubKey(targetPath))
                if ((string?)target?.GetValue("") != @"C:\Other\new.ico,0")
                    throw new InvalidOperationException("Foreign icon was overwritten");
        }
        finally
        {
            // QA only: no real drive letter or third-party registry keys.
            Release(fake, "QaOriginal", targetPath, "");
            Release(fake, "QaForeignWins", targetPath, "");
            Registry.CurrentUser.DeleteSubKeyTree(testRoot,
                throwOnMissingSubKey: false);
        }
        Console.WriteLine("OWNED_ICON_LEASE_REAL_HKCU_RESTORE_PASS");
        Console.WriteLine("OWNED_ICON_TEMPORARY_OVERRIDE_POLICY_PASS");
    }
}
