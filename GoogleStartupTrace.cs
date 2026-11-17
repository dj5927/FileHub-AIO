using System.Collections.Concurrent;
using System.Diagnostics;

namespace NASMount;

/// <summary>
/// Opt-in startup performance probe. The original rclone DEBUG line is
/// NEVER persisted, shown, or included in an error dialog: it may contain
/// OAuth token data, URLs, usernames or remote filenames. Only fixed
/// category names and monotonic relative times are recorded.
/// </summary>
internal sealed class GoogleStartupTrace
{
    internal static bool Enabled { get; set; }
    private readonly Guid profileId;
    private readonly Stopwatch timer = Stopwatch.StartNew();
    private readonly ConcurrentDictionary<string, int> categoryCounts = new();
    internal GoogleStartupTrace(Guid id) { profileId = id; }

    internal void Observe(string raw)
    {
        string category = Classify(raw);
        if (category == "other") return;
        int occurrence = categoryCounts.AddOrUpdate(category, 1, (_, n) => n + 1);
        if (occurrence <= 4 || occurrence == 10 || occurrence == 50)
            AppStore.Log("GOOGLE_STARTUP_DIAG id=" + profileId +
                " phase=" + category + " elapsedMs=" + timer.ElapsedMilliseconds +
                " occurrence=" + occurrence);
    }

    internal void Milestone(string stage) =>
        AppStore.Log("GOOGLE_STARTUP_DIAG id=" + profileId +
            " phase=" + stage + " elapsedMs=" + timer.ElapsedMilliseconds);

    internal static string Classify(string raw)
    {
        if (string.IsNullOrEmpty(raw)) return "other";
        // No actual DEBUG strings, hostnames, paths, OAuth codes, IDs, or
        // remote filenames are ever copied to the output.
        string value = raw.ToLowerInvariant();
        if (value.Contains("ratelimit") || value.Contains("rate limit") ||
            value.Contains("quotaexceeded") || value.Contains("quota exceeded") ||
            value.Contains("user rate limit"))
            return "rate_limit";
        if (value.Contains("retry") || value.Contains("backoff") ||
            value.Contains("pacer"))
            return "api_retry_or_pacer";
        if (value.Contains("refresh token") || value.Contains("token refresh") ||
            value.Contains("token expired"))
            return "oauth_refresh";
        if (value.Contains("unauthorized") || value.Contains("invalid_grant"))
            return "oauth_auth_error";
        if (value.Contains("creating backend") || value.Contains("new fs for"))
            return "backend_creation";
        if (value.Contains("starting mount") || value.Contains("mounting"))
            return "mount_setup";
        if (value.Contains("root directory") || value.Contains("root folder"))
            return "root_discovery";
        if (value.Contains("failed") || value.Contains("fatal") ||
            value.Contains("error"))
            return "backend_reported_error";
        return "other";
    }

    internal static void PolicyTest()
    {
        foreach (var (line, expected) in new[] {
            ("DEBUG oauth refresh token=SECRET-NEVER-LOG", "oauth_refresh"),
            ("pacer: low level retry url=https://host/private", "api_retry_or_pacer"),
            ("rateLimitExceeded file=TopSecret.xlsx", "rate_limit"),
            ("DEBUG : creating backend with remote /sensitive/path", "backend_creation"),
            ("2026-11-11 unexpected path /user/Desktop", "other")
        })
            if (Classify(line) != expected ||
                Classify(line).Contains("SECRET", StringComparison.Ordinal))
                throw new InvalidOperationException("Google startup log sanitizer failed");
        Console.WriteLine("GOOGLE_STARTUP_DIAG_FIXED_CATEGORY_REDACTION_PASS");
    }
}
