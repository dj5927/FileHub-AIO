using Microsoft.Win32;

namespace NASMount;

// DriveIcons\X\DefaultLabel is a second Windows Explorer label source.
// Network providers sometimes ignore the UNC MountPoints2 label until the
// next Explorer refresh, so set both for a verified FileHub-owned drive.
// Remember and restore any previous per-user name for this letter.
internal static class DriveLabelLease
{
    private const string LabelRoot =
        @"Software\Microsoft\Windows\CurrentVersion\Explorer\DriveIcons";
    private const string LeaseRoot = @"Software\NASMount\DisplayLabelLeases";

    private static string LeaseKey(NasProfile p) =>
        LeaseRoot + "\\" + p.Id.ToString("N") + "_" + p.Letter.ToUpperInvariant();

    private static string TargetKey(NasProfile p) =>
        LabelRoot + "\\" + p.Letter.ToUpperInvariant() + "\\DefaultLabel";

    internal static void Apply(NasProfile p)
    {
        if (p.Protocol == "SMB" || p.Letter.Length != 1 ||
            !RemoteMount.HasManagedRecord(p) || !RemoteMount.IsMounted(p) ||
            RemoteMount.HasVerifiedOtherVendorMapping(p)) return;
        try
        {
            string value = MountPresentation.SafeLabel(p.DisplayName);
            string path = TargetKey(p);
            using (var lease = Registry.CurrentUser.CreateSubKey(LeaseKey(p)))
            {
                if (lease is null) return;
                if (lease.GetValue("Ready") is not int ready || ready != 1)
                {
                    using var existing = Registry.CurrentUser.OpenSubKey(path);
                    string? prior = existing?.GetValue("") as string;
                    lease.SetValue("Ready", 1, RegistryValueKind.DWord);
                    lease.SetValue("HadPrior", prior is null ? 0 : 1,
                        RegistryValueKind.DWord);
                    lease.SetValue("Prior", prior ?? "", RegistryValueKind.String);
                }
                lease.SetValue("OwnedLabel", value, RegistryValueKind.String);
            }
            using var target = Registry.CurrentUser.CreateSubKey(path);
            target?.SetValue("", value, RegistryValueKind.String);
        }
        catch (Exception ex)
        {
            AppStore.Log("OWNED_NETWORK_LABEL_APPLY_FAILED id=" + p.Id +
                " type=" + ex.GetType().Name);
        }
    }

    internal static void Restore(NasProfile p)
    {
        if (p.Letter.Length != 1) return;
        try
        {
            string key = LeaseKey(p);
            using var lease = Registry.CurrentUser.OpenSubKey(key);
            if (lease?.GetValue("Ready") is not int ready || ready != 1)
                return;
            string? ours = lease.GetValue("OwnedLabel") as string;
            using var target = Registry.CurrentUser.OpenSubKey(TargetKey(p), true);
            if (target is not null &&
                string.Equals(target.GetValue("") as string, ours,
                    StringComparison.Ordinal))
            {
                if (lease.GetValue("HadPrior") is int had && had == 1)
                    target.SetValue("", lease.GetValue("Prior") as string ?? "",
                        RegistryValueKind.String);
                else
                    target.DeleteValue("", throwOnMissingValue: false);
            }
            lease.Dispose();
            Registry.CurrentUser.DeleteSubKeyTree(key,
                throwOnMissingSubKey: false);
        }
        catch (Exception ex)
        {
            AppStore.Log("OWNED_NETWORK_LABEL_RESTORE_FAILED id=" + p.Id +
                " type=" + ex.GetType().Name);
        }
    }
}
