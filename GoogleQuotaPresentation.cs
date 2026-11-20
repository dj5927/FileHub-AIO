using System.Collections.Concurrent;

namespace NASMount;

/// <summary>
/// A new Google mount initially reports an unknown size while its patched
/// backend fetches quota in the background. Announce exactly one Shell
/// refresh after real cached Total/Free become available. The background
/// probe never calls Google, lists folders, or touches user files.
/// </summary>
internal static class GoogleQuotaPresentation
{
    private const long UnknownQuota = 1L << 50;
    private static readonly ConcurrentDictionary<Guid, byte> Running = new();

    internal static void Schedule(NasProfile profile)
    {
        if (profile.Protocol != "GoogleDrive" ||
            !GoogleAsyncQuotaEngine.IsAvailable ||
            !RemoteMount.IsMounted(profile) ||
            !Running.TryAdd(profile.Id, 1))
            return;
        var worker = new Thread(() =>
        {
            try
            {
                // Real quota fetch is deliberately deferred until after the
                // first root browse. Keep polling the local WinFsp cache
                // (without making an extra Google API call).
                for (int attempt = 0; attempt < 60; attempt++)
                {
                    if (!RemoteMount.IsMounted(profile)) return;
                    Thread.Sleep(1000);
                    if (!RemoteMount.IsMounted(profile)) return;
                    try
                    {
                        var drive = new DriveInfo(profile.Letter.ToUpperInvariant() + ":\\");
                        long total = drive.TotalSize;
                        long free = drive.AvailableFreeSpace;
                        if (total <= 0 || total == UnknownQuota ||
                            free < 0 || free > total) continue;
                        AppStore.Log("GOOGLE_REAL_QUOTA_READY id=" + profile.Id +
                            " totalBytes=" + total + " freeBytes=" + free +
                            " attempt=" + attempt);
                        ExplorerDriveIcon.NotifyComputerChanged();
                        return;
                    }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                }
                AppStore.Log("GOOGLE_REAL_QUOTA_PENDING id=" + profile.Id);
            }
            finally { Running.TryRemove(profile.Id, out _); }
        })
        {
            IsBackground = true,
            Name = "FileHub.GoogleQuotaPresentation"
        };
        worker.Start();
    }
}
