using System.Text;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Historical local evidence for WinFsp UNC servers that used to be owned by
/// FileHub AIO. Older builds discarded the MountPoints2 key before Explorer
/// dropped its cached network tile. Once that key is gone, querying the
/// registry and a NEW Shell.Application.NameSpace(17) loses the server ID.
/// We recover identifiers ONLY from our own local logs, not arbitrary drives.
/// This is a bounded, read-only scan; it never reads remote/cloud files.
/// </summary>
internal static class RetiredMountHistory
{
    private static readonly Regex OwnedId = new(
        // Only server IDs from a real UNC name: never infer ownership from
        // "OLD_GHOST_GUID_CLEANUP_FINISHED" or synthetic policy-test IDs.
        @"(?i)\\{2}NASMount-([a-f0-9]{8})\\",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    // Only own servers, no arbitrary UNC names or drive letters.
    internal static IReadOnlySet<string> ExtractIdentifiers(string text)
        => OwnedId.Matches(text).Select(x => x.Groups[1].Value.ToLowerInvariant())
            .ToHashSet(StringComparer.OrdinalIgnoreCase);

    internal static IReadOnlySet<string> ScanRecentLocalLogs(string? logsDirectory = null)
    {
        string folder = logsDirectory ?? Path.Combine(AppStore.DirectoryPath, "logs");
        var output = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (!Directory.Exists(folder)) return output;
            var logs = new DirectoryInfo(folder).EnumerateFiles("*.log")
                .Where(f => f.LastWriteTimeUtc >= DateTime.UtcNow.AddDays(-30))
                .OrderByDescending(f => f.LastWriteTimeUtc)
                .Take(35);
            foreach (var file in logs)
            {
                try
                {
                    // Read at most the LAST 3 MiB of each log, not the
                    // potentially unbounded history or any secret files.
                    using var stream = new FileStream(file.FullName, FileMode.Open,
                        FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    long bytes = Math.Min(stream.Length, 3L * 1024 * 1024);
                    stream.Seek(-bytes, SeekOrigin.End);
                    byte[] buffer = new byte[(int)bytes];
                    int n = 0;
                    while (n < buffer.Length)
                    {
                        int got = stream.Read(buffer, n, buffer.Length - n);
                        if (got <= 0) break;
                        n += got;
                    }
                    output.UnionWith(ExtractIdentifiers(Encoding.UTF8.GetString(buffer, 0, n)));
                    if (output.Count >= 100) break;
                }
                catch (IOException) { /* one rotating log is not fatal */ }
                catch (UnauthorizedAccessException) { }
            }
            // Only allow bounded work. Avoid trying to clear every GUID ever
            // used by the program; the rest can be diagnosed explicitly.
            return output.OrderBy(x => x, StringComparer.Ordinal)
                .Take(100).ToHashSet(StringComparer.OrdinalIgnoreCase);
        }
        catch (Exception ex)
        {
            AppStore.Log("RETIRED_HISTORY_SCAN_WARNING " + ex.Message);
            return output;
        }
    }

    internal static void PolicyTest()
    {
        const string sample = "old \\\\NASMount-af7212e7\\Drive " +
            "live \\\\NASMount-e10ab8ec\\Another " +
            "OLD_GHOST_GUID_CLEANUP_FINISHED 1350aaaa " +
            "also \\\\RaiDrive-12345678\\Share " +
            "NASMount-abcdef0123456789";
        var ids = ExtractIdentifiers(sample);
        if (ids.Count != 2 || !ids.Contains("af7212e7") ||
            !ids.Contains("e10ab8ec"))
            throw new InvalidOperationException(
                "Only exact retired NASMount 8-digit servers may be recovered");
        Console.WriteLine("RETIRED_GHOST_HISTORY_SCOPE_TEST_PASS");
    }
}
