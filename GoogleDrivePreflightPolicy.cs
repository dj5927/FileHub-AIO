namespace NASMount;

/// <summary>
/// The very first Drive list after OAuth can take longer than a NAS TCP check.
/// This policy only affects the disposable, read-only rclone lsf preflight,
/// never a mounted process or its VFS cache.
/// </summary>
internal static class GoogleDrivePreflightPolicy
{
    // Cloud engines can take several requests to warm the first filesystem
    // list after OAuth/cookie startup. Retry these disposable READ-ONLY
    // probes internally; never retry the real writable mount process.
    internal static int AttemptCount(string protocol) =>
        protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox" ? 3 : 1;
    internal static TimeSpan Timeout(string protocol, int attempt) =>
        protocol switch
        {
            "GoogleDrive" => TimeSpan.FromSeconds(attempt switch
                { 1 => 40, 2 => 50, _ => 60 }),
            "OneDrive" => TimeSpan.FromSeconds(attempt switch
                { 1 => 40, 2 => 50, _ => 60 }),
            "MyBox" => TimeSpan.FromSeconds(attempt switch
                { 1 => 40, 2 => 50, _ => 60 }),
            "TeraBox" => TimeSpan.FromSeconds(attempt switch
                { 1 => 25, 2 => 35, _ => 45 }),
            _ => TimeSpan.FromSeconds(14)
        };
    internal static TimeSpan RetryDelay(string protocol, int attempt) =>
        protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox"
            ? TimeSpan.FromSeconds(attempt == 1 ? 2 : 3)
            : TimeSpan.Zero;
    internal static TimeSpan ExplorerBrowseTimeout(string protocol) =>
        protocol is "GoogleDrive" or "OneDrive" or "MyBox" ? TimeSpan.FromSeconds(45) :
        protocol == "TeraBox" ? TimeSpan.FromSeconds(35) :
        TimeSpan.FromSeconds(12);
    internal static bool IsTransient(string protocol, bool timedOut, string error)
    {
        if (protocol is not ("GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox")) return false;
        // Authentication and permission failures will NOT heal by waiting.
        if (new[] { "401", "403", "invalid_grant", "invalid credentials",
            "invalid cookie", "authentication failed", "access denied",
            "unauthorized", "forbidden" }
            .Any(needle => error.Contains(needle, StringComparison.OrdinalIgnoreCase)))
            return false;
        if (timedOut) return true;
        return new[] { "429", "500", "502", "503", "504", "rate limit",
            "too many requests", "connection reset", "connection refused",
            "timeout", "timed out", "temporary", "temporarily unavailable",
            "network is unreachable", "unexpected eof",
            "eof", "i/o timeout", "connection aborted" }
            .Any(needle => error.Contains(needle, StringComparison.OrdinalIgnoreCase));
    }
}
