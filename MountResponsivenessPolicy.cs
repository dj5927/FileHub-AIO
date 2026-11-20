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
        bool fastRead, bool officeRead, bool lockBridge = false)
    {
        if (officeRead) return "20s";
        if (fastRead) return "15s";
        // The WebDAV LockBridge independently observes the actual NAS with
        // PROPFIND every 2s, then explicitly refreshes rclone only for a
        // proven external change. With a six-second rclone directory cache,
        // a server's asynchronous write-back/mtime normalization of OUR OWN
        // first Excel save gets re-imported between Ctrl+S operations and
        // can be misread by Excel as another user's write. Keep the local
        // VFS view stable while its monitored external-change path remains
        // active. Never use this policy for unbridged WebDAV mounts.
        if (protocol == "WebDAV" && lockBridge) return "30m";
        return protocol switch
        {
            "WebDAV" => "6s",
            // Google supports change notifications. Keeping entries for
            // 30 minutes avoids refetching the whole folder on each Explorer
            // F5/file open; changes from elsewhere are picked up by polling.
            "GoogleDrive" => "30m",
            "OneDrive" => "20s",
            "MyBox" => "15s",
            "TeraBox" => "15s",
            _ => "5s"
        };
    }

    internal static void PolicyTest()
    {
        if (DirectoryCacheTime("WebDAV", false, false) != "6s" ||
            DirectoryCacheTime("WebDAV", false, false, true) != "30m" ||
            DirectoryCacheTime("WebDAV", true, false, true) != "15s" ||
            DirectoryCacheTime("SFTP", false, false, true) != "5s" ||
            DirectoryCacheTime("GoogleDrive", false, false) != "30m" ||
            DirectoryCacheTime("OneDrive", false, false) != "20s" ||
            DirectoryCacheTime("TeraBox", false, false) != "15s" ||
            DirectoryCacheTime("SFTP", false, false) != "5s" ||
            DirectoryCacheTime("WebDAV", true, false) != "15s" ||
            DirectoryCacheTime("WebDAV", false, true) != "20s")
            throw new InvalidOperationException("VFS metadata cache policy failed");
        Console.WriteLine("MOUNT_DIRECTORY_CACHE_RESPONSIVENESS_POLICY_PASS");
        Console.WriteLine("WEBDAV_LOCKBRIDGE_EXCEL_OWN_SAVE_METADATA_STABLE_POLICY_PASS");
    }
}
