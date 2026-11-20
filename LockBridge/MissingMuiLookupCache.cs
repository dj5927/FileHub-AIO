using System.Collections.Concurrent;
using System.Diagnostics;

namespace NASMount.LockBridge;

/// <summary>
/// The Windows PE version/icon resource loader probes four or more locale
/// paths (ko, ko-KR, en, en-US) for each executable's *.exe.mui. When those
/// language folders do not exist, blindly forwarding every child to a second
/// WinFsp mount repeats the same costly failed WebDAV metadata lookup many
/// times. Remember proven ABSENT parents and files for a bounded interval.
/// Positive lookup handling and every Office path remain untouched.
/// </summary>
internal sealed class MissingMuiLookupCache
{
    private readonly ConcurrentDictionary<string, long> _missing =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, long> _presentParents =
        new(StringComparer.OrdinalIgnoreCase);
    private static readonly long TtlTicks = 20 * Stopwatch.Frequency;

    internal static bool IsMui(string path) =>
        path.EndsWith(".mui", StringComparison.OrdinalIgnoreCase);

    private static bool IsCurrent(ConcurrentDictionary<string, long> dictionary,
        string key)
    {
        if (!dictionary.TryGetValue(key, out long until)) return false;
        if (Stopwatch.GetTimestamp() < until) return true;
        dictionary.TryRemove(key, out _);
        return false;
    }

    internal bool IsMissing(string path) => IsCurrent(_missing, path);
    internal bool IsKnownPresentParent(string path) =>
        IsCurrent(_presentParents, path);

    internal void Missing(string path) => _missing[path] =
        Stopwatch.GetTimestamp() + TtlTicks;

    internal void ParentExists(string path) => _presentParents[path] =
        Stopwatch.GetTimestamp() + TtlTicks;

    internal void Invalidate(string changed)
    {
        foreach (var key in _missing.Keys)
            if (IsSameOrChild(key, changed)) _missing.TryRemove(key, out _);
        foreach (var key in _presentParents.Keys)
            if (IsSameOrChild(key, changed)) _presentParents.TryRemove(key, out _);
    }

    private static bool IsSameOrChild(string path, string parent) =>
        path.Equals(parent, StringComparison.OrdinalIgnoreCase) ||
        path.StartsWith(parent.TrimEnd('\\') + "\\",
            StringComparison.OrdinalIgnoreCase);

    internal static void PolicyTest()
    {
        var c = new MissingMuiLookupCache();
        var child = @"\apps\ko\Demo.exe.mui";
        var parent = @"\apps\ko";
        if (!IsMui(child) || IsMui(@"\apps\11.xlsx") || c.IsMissing(parent))
            throw new InvalidOperationException("MUI filter/cache invalid");
        c.Missing(parent);
        if (!c.IsMissing(parent.ToUpperInvariant()))
            throw new InvalidOperationException("Negative locale directory not cached");
        c.Missing(child);
        c.Invalidate(parent);
        if (c.IsMissing(parent) || c.IsMissing(child))
            throw new InvalidOperationException("New locale directory did not invalidate missing files");
        c.ParentExists(parent);
        if (!c.IsKnownPresentParent(parent))
            throw new InvalidOperationException("Present locale directory not cached");
        Console.WriteLine("MISSING_EXE_MUI_LOCALE_NEGATIVE_CACHE_PASS");
    }
}
