namespace NASMount;

/// <summary>
/// Avoid forcing a remote directory listing on essentially every Windows
/// Explorer metadata call. v1.3.53 used 1s on all normal mounts while the
/// WebDAV lock bridge ALSO polls recently observed folders every ~2s.
/// rclone locally reflects its own writes immediately. The lock bridge
/// issues an explicit cache refresh when a watched remote folder changes.
/// Do not weaken the independent write-back / Excel lease safety checks.
/// </summary>
internal static class MountResponsivenessPolicy
{
    internal static string DirectoryCacheTime(string protocol,
        bool fastRead, bool officeRead)
    {
        if (officeRead) return "20s";
        if (fastRead) return "15s";
        return protocol switch
        {
            "WebDAV" => "6s",
            "GoogleDrive" => "20s",
            "OneDrive" => "20s",
            "MyBox" => "15s",
            "TeraBox" => "15s",
            _ => "5s"
        };
    }

    internal static void PolicyTest()
    {
        if (DirectoryCacheTime("WebDAV", false, false) != "6s" ||
            DirectoryCacheTime("GoogleDrive", false, false) != "20s" ||
            DirectoryCacheTime("OneDrive", false, false) != "20s" ||
            DirectoryCacheTime("TeraBox", false, false) != "15s" ||
            DirectoryCacheTime("SFTP", false, false) != "5s" ||
            DirectoryCacheTime("WebDAV", true, false) != "15s" ||
            DirectoryCacheTime("WebDAV", false, true) != "20s")
            throw new InvalidOperationException("VFS metadata cache policy failed");
        Console.WriteLine("MOUNT_DIRECTORY_CACHE_RESPONSIVENESS_POLICY_PASS");
    }
}
