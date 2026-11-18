namespace NASMount;

/// <summary>
/// Explorer repeatedly calls WinFsp GetVolumeInfo when showing This PC.
/// rclone's VFS Statfs() calls the Drive backend's About API on each
/// DirCacheTime expiry (20 seconds with the current Google profile). If the
/// API retries or stalls, Explorer waits for the same callback and the free
/// space bar temporarily vanishes. Disable ONLY the optional About feature
/// for Google mounts; other backend features and all filesystem operations
/// remain available.
///
/// Without About, rclone reports virtual/unknown capacity. It is NOT a real
/// Google quota. Never invent a 15 GiB quota for paid / shared accounts.
/// </summary>
internal static class GoogleExplorerStabilityPolicy
{
    internal static void AppendArgs(ICollection<string> args, NasProfile profile,
        bool asynchronousQuota = false)
    {
        if (profile.Protocol != "GoogleDrive") return;
        // A verified FileHub-owned rclone moves About off Explorer's
        // synchronous Statfs callback and caches actual Total/Used/Free.
        if (asynchronousQuota) return;
        args.Add("--disable");
        args.Add("About");
    }

    internal static void SelfTest()
    {
        var google = new List<string>();
        AppendArgs(google, new NasProfile { Protocol = "GoogleDrive" });
        if (!google.SequenceEqual(["--disable", "About"]))
            throw new InvalidOperationException("Google About suppression not configured");
        var realQuota = new List<string>();
        AppendArgs(realQuota, new NasProfile { Protocol = "GoogleDrive" },
            asynchronousQuota: true);
        if (realQuota.Count != 0)
            throw new InvalidOperationException(
                "Verified nonblocking quota engine must retain About support");
        foreach (string protocol in new[]
                 { "WebDAV", "SFTP", "FTP", "TeraBox", "MyBox", "OneDrive", "SMB" })
        {
            var other = new List<string>();
            AppendArgs(other, new NasProfile { Protocol = protocol });
            if (other.Count != 0)
                throw new InvalidOperationException(
                    "Google volume workaround leaked into " + protocol);
        }
        Console.WriteLine("GOOGLE_EXPLORER_NO_REMOTE_ABOUT_QUOTA_POLICY_PASS");
        Console.WriteLine("GOOGLE_CACHED_REAL_ABOUT_QUOTA_POLICY_PASS");
    }
}
