namespace NASMount;

/// <summary>
/// Google Drive's default rclone request pacer permits a large startup burst
/// before API rate limits cause exponential retries. Use a conservative
/// bounded burst for true cold mounts; never bypass quota enforcement.
/// Every normal app exit still safely disconnects all remote volumes.
/// </summary>
internal static class GoogleMountPacingPolicy
{
    // 250 ms and a burst of just TWO serialized independent metadata
    // requests in Explorer, so opening a folder after cache expiry could
    // appear stuck. Keep a 100 ms steady pace (10/s) with a modest burst.
    internal const string MinSleep = "100ms";
    internal const string Burst = "8";

    internal static void AppendArgs(ICollection<string> args, NasProfile profile)
    {
        if (profile.Protocol != "GoogleDrive") return;
        args.Add("--drive-pacer-min-sleep");
        args.Add(MinSleep);
        args.Add("--drive-pacer-burst");
        args.Add(Burst);
    }

    internal static void SelfTest()
    {
        var google = new List<string>();
        AppendArgs(google, new NasProfile { Protocol = "GoogleDrive" });
        if (!google.SequenceEqual(new[]
            {
                "--drive-pacer-min-sleep", "100ms",
                "--drive-pacer-burst", "8"
            }))
            throw new InvalidOperationException("Google pacer flags changed");
        foreach (string protocol in new[]
            { "WebDAV", "SFTP", "FTP", "OneDrive", "TeraBox", "MyBox", "SMB" })
        {
            var other = new List<string>();
            AppendArgs(other, new NasProfile { Protocol = protocol });
            if (other.Count != 0)
                throw new InvalidOperationException(
                    "Google-specific flags leaked to " + protocol);
        }
        Console.WriteLine("GOOGLE_COLD_MOUNT_PACING_NO_KEEPALIVE_POLICY_PASS");
    }
}
