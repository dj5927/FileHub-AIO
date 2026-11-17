namespace NASMount;

/// <summary>
/// Records explicit disconnect intent only for the lifetime of one FileHub AIO
/// process. It never modifies the saved AutoConnect setting: restarting the
/// app starts a fresh session, while a successful explicit connect unpauses
/// only that profile.
/// </summary>
internal sealed class SessionReconnectPause
{
    private readonly HashSet<Guid> _suspended = [];

    internal bool IsSuspended(Guid profileId) => _suspended.Contains(profileId);

    internal void ManualDisconnectRequested(Guid profileId) =>
        _suspended.Add(profileId);

    internal void ManualConnectionSucceeded(Guid profileId) =>
        _suspended.Remove(profileId);

    internal void RemoveDeletedProfile(Guid profileId) =>
        _suspended.Remove(profileId);

    internal bool ShouldAttemptAutoConnect(NasProfile profile, bool isConnected,
        DateTime? retryAfterUtc, DateTime nowUtc) =>
        profile.AutoConnect &&
        !isConnected &&
        !_suspended.Contains(profile.Id) &&
        (!retryAfterUtc.HasValue || retryAfterUtc.Value <= nowUtc);

    internal static void SelfTest()
    {
        var webdav = new NasProfile { Protocol = "WebDAV", AutoConnect = true };
        var google = new NasProfile { Protocol = "GoogleDrive", AutoConnect = true };
        var tera = new NasProfile { Protocol = "TeraBox", AutoConnect = true };
        var policy = new SessionReconnectPause();
        DateTime now = DateTime.UtcNow;
        void Check(bool condition, string label)
        {
            if (!condition) throw new InvalidOperationException(
                "SESSION_RECONNECT_PAUSE_TEST_FAIL " + label);
        }
        Check(policy.ShouldAttemptAutoConnect(webdav, false, null, now), "startup auto");
        Check(!policy.ShouldAttemptAutoConnect(webdav, true, null, now), "already connected");
        Check(!policy.ShouldAttemptAutoConnect(webdav, false, now.AddMinutes(2), now),
            "transient retry cooldown");
        policy.ManualDisconnectRequested(webdav.Id);
        policy.ManualDisconnectRequested(google.Id);
        policy.ManualDisconnectRequested(tera.Id);
        foreach (var profile in new[] {webdav, google, tera})
        {
            Check(!policy.ShouldAttemptAutoConnect(profile, false, null, now),
                "manual stop " + profile.Protocol);
            Check(!policy.ShouldAttemptAutoConnect(profile, false, now.AddMinutes(-10),
                now.AddHours(1)), "timer retry cannot reconnect " + profile.Protocol);
            Check(profile.AutoConnect, "auto-connect configuration untouched " + profile.Protocol);
        }
        // Failed explicit connection must NOT resume automatic reconnect.
        Check(policy.IsSuspended(google.Id), "manual failure keeps hold");
        policy.ManualConnectionSucceeded(google.Id);
        Check(policy.ShouldAttemptAutoConnect(google, false, null, now),
            "successful manual connect resumes Google");
        Check(policy.IsSuspended(webdav.Id) && policy.IsSuspended(tera.Id),
            "other profiles stay paused");
        policy.RemoveDeletedProfile(tera.Id);
        Check(!policy.IsSuspended(tera.Id), "deleted profile cleanup");
        Check(new SessionReconnectPause().ShouldAttemptAutoConnect(webdav, false, null, now),
            "new process resets session suppression");
        webdav.AutoConnect = false;
        Check(!policy.ShouldAttemptAutoConnect(webdav, false, null, now),
            "permanent configured disable still respected");
        Console.WriteLine("SESSION_MANUAL_DISCONNECT_RECONNECT_POLICY_PASS");
    }
}
